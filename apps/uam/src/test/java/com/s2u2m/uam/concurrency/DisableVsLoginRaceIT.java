package com.s2u2m.uam.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.application.DisableUserUseCase;
import com.s2u2m.uam.application.GetUserUseCase;
import com.s2u2m.uam.application.LoginCommand;
import com.s2u2m.uam.application.LoginUseCase;
import com.s2u2m.uam.application.UserStatusCommand;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.OnlineUserSummary;
import com.s2u2m.uam.domain.model.OperatorContext;
import com.s2u2m.uam.domain.model.PageResult;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.model.UserWithAccountCount;
import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.policy.SessionPolicy;
import com.s2u2m.uam.domain.port.AccountRepository;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.CredentialRepository;
import com.s2u2m.uam.domain.port.LoginThrottle;
import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.domain.port.SessionStore;
import com.s2u2m.uam.domain.port.TransactionRunner;
import com.s2u2m.uam.domain.port.UserRepository;
import com.s2u2m.uam.domain.port.UserStatusLock;
import com.s2u2m.uam.domain.port.WriteOperationLog;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.UserFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * T112：停用与登录并发——两种（实为全部六种）交错顺序都不会留下已停用用户的有效会话。
 *
 * <p>登录有两个相关动作：W（写入会话）与 R（写入后复查用户状态，W 先于 R）；停用有两个：C（事务提交）与 T（提交后终止会话，C 先于 T）。 {@link
 * #everyInterleavingLeavesNoLiveSessionOfTheDisabledUser} 用装饰过的端口（真实的 PostgreSQL 与
 * Redis）把这四个动作按给定顺序<b>确定性</b> 地逐一放行，穷举全部 6 种合法顺序；{@link
 * #realConcurrentLoginsAndDisablesNeverLeaveALiveSession} 再用真实的并发请求多轮重复作补充（结果断言是确定的， 交错由调度决定）。
 */
class DisableVsLoginRaceIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final String IP = "203.0.113.7";

  /** 被排序的动作；P 是登录开头的用户状态读取（凭据校验），总是第一个，保证登录通过前置校验后才进入被测的交错。 */
  enum Step {
    P,
    W,
    R,
    C,
    T
  }

  /** 按给定顺序放行动作：轮到自己才执行，其余线程等待。 */
  static final class Sequencer {
    private final List<Step> order;
    private int next;

    Sequencer(List<Step> order) {
      this.order = order;
    }

    synchronized <T> T run(Step step, Supplier<T> action) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (next < order.size() && order.get(next) != step) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new IllegalStateException("等不到轮次: " + step + "，当前应为 " + order.get(next));
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(this, remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      try {
        return action.get();
      } finally {
        next++;
        notifyAll();
      }
    }
  }

  static Stream<List<Step>> interleavings() {
    return Stream.of(
        List.of(Step.P, Step.W, Step.R, Step.C, Step.T),
        List.of(Step.P, Step.W, Step.C, Step.R, Step.T),
        List.of(Step.P, Step.W, Step.C, Step.T, Step.R),
        List.of(Step.P, Step.C, Step.W, Step.R, Step.T),
        List.of(Step.P, Step.C, Step.W, Step.T, Step.R),
        List.of(Step.P, Step.C, Step.T, Step.W, Step.R));
  }

  @Value("${local.server.port}")
  int port;

  @Autowired UserRepository users;
  @Autowired AccountRepository accounts;
  @Autowired CredentialRepository credentials;
  @Autowired PasswordHasher hasher;
  @Autowired SessionStore sessions;
  @Autowired LoginThrottle throttle;
  @Autowired LoginThrottlePolicy throttlePolicy;
  @Autowired SessionPolicy sessionPolicy;
  @Autowired Clock clock;
  @Autowired TransactionRunner transactions;
  @Autowired UserStatusLock statusLock;
  @Autowired WriteOperationLog writeLog;
  @Autowired GetUserUseCase getUser;

  private UserFixtures data;
  private UUID operatorUser;
  private UUID target;
  private UUID targetAccount;

  @BeforeEach
  void setUp() {
    data = new UserFixtures(jdbc);
    operatorUser = data.user("运维甲");
    target = data.user("张三");
    targetAccount = data.account(target, "zhangsan");
    data.credential(targetAccount, hasher.hash(PASSWORD));
  }

  private void assertNoLiveSessionOfTheTarget() {
    assertThat(redis.keys("uam:sess:*")).isEmpty();
    assertThat(redis.keys("uam:sref:*")).isEmpty();
    assertThat(redis.keys("uam:acct:*")).isEmpty();
    assertThat(redis.opsForZSet().zCard("uam:online")).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("interleavings")
  void everyInterleavingLeavesNoLiveSessionOfTheDisabledUser(List<Step> order) throws Exception {
    Sequencer sequencer = new Sequencer(order);
    AtomicCounter findByIdCalls = new AtomicCounter();
    UserRepository gatedUsers =
        new DelegatingUsers(users) {
          @Override
          public Optional<User> findById(UUID id) {
            // 登录里第一次读用户是凭据校验 P，第二次才是「写入会话后的复查」R
            int call = findByIdCalls.increment();
            if (call == 1) {
              return sequencer.run(Step.P, () -> super.findById(id));
            }
            if (call == 2) {
              return sequencer.run(Step.R, () -> super.findById(id));
            }
            return super.findById(id);
          }
        };
    SessionStore loginSessions =
        new DelegatingSessions(sessions) {
          @Override
          public Optional<SessionRef> create(Session session, String tokenHash) {
            return sequencer.run(Step.W, () -> super.create(session, tokenHash));
          }
        };
    LoginUseCase login =
        new LoginUseCase(
            gatedUsers,
            accounts,
            credentials,
            hasher,
            loginSessions,
            throttle,
            throttlePolicy,
            sessionPolicy,
            clock);

    TransactionRunner gatedTransactions =
        new TransactionRunner() {
          @Override
          public <T> T inTransaction(Supplier<T> work) {
            return sequencer.run(Step.C, () -> transactions.inTransaction(work));
          }
        };
    SessionStore disableSessions =
        new DelegatingSessions(sessions) {
          @Override
          public int terminateAllForUser(Collection<UUID> accountIds) {
            return sequencer.run(Step.T, () -> super.terminateAllForUser(accountIds));
          }
        };
    DisableUserUseCase disable =
        new DisableUserUseCase(
            users, accounts, disableSessions, statusLock, gatedTransactions, getUser, writeLog);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Object> loginOutcome =
        pool.submit(
            () -> {
              try {
                return login.login(new LoginCommand("zhangsan", PASSWORD, IP));
              } catch (DomainException e) {
                return e.errorCode();
              }
            });
    Future<?> disabled =
        pool.submit(
            () ->
                disable.disable(
                    new UserStatusCommand(
                        new OperatorContext(operatorUser, UUID.randomUUID(), UUID.randomUUID()),
                        "admin-center",
                        target)));
    Object outcome = loginOutcome.get(60, TimeUnit.SECONDS);
    disabled.get(60, TimeUnit.SECONDS);
    pool.shutdown();

    assertThat(users.findById(target).orElseThrow().status()).isEqualTo(UserStatus.DISABLED);
    assertNoLiveSessionOfTheTarget();
    boolean loginSawActiveUser = order.indexOf(Step.R) < order.indexOf(Step.C);
    if (loginSawActiveUser) {
      assertThat(outcome)
          .as("复查早于提交，登录成功，随后会话被停用终止")
          .isInstanceOf(com.s2u2m.uam.application.LoginResult.class);
    } else {
      assertThat(outcome).as("复查晚于提交，登录被统一拒绝").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }
  }

  @RepeatedTest(12)
  void realConcurrentLoginsAndDisablesNeverLeaveALiveSession() throws Exception {
    ApiClient api = new ApiClient(port, TEST_SERVICE_KEY);
    jdbc.update("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?", target);
    CountDownLatch gate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<Future<Response>> futures = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      futures.add(
          pool.submit(
              () -> {
                gate.await();
                return api.login("zhangsan", PASSWORD, IP);
              }));
    }
    futures.add(
        pool.submit(
            () -> {
              gate.await();
              Map<String, String> headers = new LinkedHashMap<>();
              headers.put("X-Operator-User-Id", operatorUser.toString());
              headers.put("X-Operator-Account-Id", UUID.randomUUID().toString());
              headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
              return api.postJson("/v1/users/" + target + "/disable", Map.of(), headers);
            }));
    gate.countDown();
    for (Future<Response> future : futures) {
      int status = future.get(60, TimeUnit.SECONDS).status();
      assertThat(status).isIn(200, 401);
    }
    pool.shutdown();

    assertThat(users.findById(target).orElseThrow().status()).isEqualTo(UserStatus.DISABLED);
    assertNoLiveSessionOfTheTarget();
  }

  // ---------------------------------------------------------------- 装饰器

  /** 简单计数器（避免在 lambda 里用数组）。 */
  static final class AtomicCounter {
    private int value;

    synchronized int increment() {
      return ++value;
    }
  }

  /** 把全部调用委托给真实仓储，测试里只覆盖需要闸门的方法。 */
  static class DelegatingUsers implements UserRepository {
    private final UserRepository delegate;

    DelegatingUsers(UserRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<User> findById(UUID id) {
      return delegate.findById(id);
    }

    @Override
    public void insert(User user) {
      delegate.insert(user);
    }

    @Override
    public long count() {
      return delegate.count();
    }

    @Override
    public long countActive() {
      return delegate.countActive();
    }

    @Override
    public boolean updateStatus(UUID id, UserStatus status) {
      return delegate.updateStatus(id, status);
    }

    @Override
    public PageResult<UserWithAccountCount> search(String query, int page, int size) {
      return delegate.search(query, page, size);
    }

    @Override
    public Map<UUID, String> findNames(Collection<UUID> ids) {
      return delegate.findNames(ids);
    }
  }

  /** 把全部调用委托给真实会话存储，测试里只覆盖需要闸门的方法。 */
  static class DelegatingSessions implements SessionStore {
    private final SessionStore delegate;

    DelegatingSessions(SessionStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<SessionRef> create(Session session, String tokenHash) {
      return delegate.create(session, tokenHash);
    }

    @Override
    public Optional<Session> find(String tokenHash) {
      return delegate.find(tokenHash);
    }

    @Override
    public Optional<Session> touch(String tokenHash, Session touched) {
      return delegate.touch(tokenHash, touched);
    }

    @Override
    public boolean terminateByRef(SessionRef ref, boolean markTerminated) {
      return delegate.terminateByRef(ref, markTerminated);
    }

    @Override
    public boolean wasTerminated(String tokenHash) {
      return delegate.wasTerminated(tokenHash);
    }

    @Override
    public int terminateAllForUser(Collection<UUID> accountIds) {
      return delegate.terminateAllForUser(accountIds);
    }

    @Override
    public List<OnlineUserSummary> listOnline(Instant now) {
      return delegate.listOnline(now);
    }

    @Override
    public List<Session> sessionsOfAccounts(Collection<UUID> accountIds) {
      return delegate.sessionsOfAccounts(accountIds);
    }
  }
}
