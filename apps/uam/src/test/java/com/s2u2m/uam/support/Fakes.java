package com.s2u2m.uam.support;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.Credential;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.port.AccountRepository;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.CredentialRepository;
import com.s2u2m.uam.domain.port.LoginThrottle;
import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.domain.port.SessionStore;
import com.s2u2m.uam.domain.port.TransactionRunner;
import com.s2u2m.uam.domain.port.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

/** 单元测试用的假端口（内存实现），领域与用例测试不碰数据库与 Redis。 */
public final class Fakes {

  private Fakes() {}

  /** 可手动推进的时钟。 */
  public static final class FakeClock implements Clock {
    private Instant now;

    public FakeClock(Instant start) {
      this.now = start;
    }

    @Override
    public Instant now() {
      return now;
    }

    public void advance(Duration duration) {
      now = now.plus(duration);
    }
  }

  /** 内存用户仓储。 */
  public static final class FakeUsers implements UserRepository {
    public final Map<UUID, User> byId = new LinkedHashMap<>();

    @Override
    public Optional<User> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public void insert(User user) {
      byId.put(user.id(), user);
    }

    @Override
    public long count() {
      return byId.size();
    }
  }

  /** 内存账号仓储；按规范化账号名唯一。 */
  public static final class FakeAccounts implements AccountRepository {
    public final Map<UUID, Account> byId = new LinkedHashMap<>();

    @Override
    public Optional<Account> findByLoginName(LoginName name) {
      return byId.values().stream()
          .filter(a -> a.loginName().normalized().equals(name.normalized()))
          .findFirst();
    }

    @Override
    public boolean existsByLoginName(LoginName name) {
      return findByLoginName(name).isPresent();
    }

    @Override
    public void insert(Account account) {
      if (existsByLoginName(account.loginName())) {
        throw new DomainException(ErrorCode.ACCOUNT_NAME_TAKEN, "账号名已被使用");
      }
      byId.put(account.id(), account);
    }
  }

  /** 内存凭据仓储；可设置在插入时失败，用来验证「不留半成品」。 */
  public static final class FakeCredentials implements CredentialRepository {
    public final Map<UUID, String> hashes = new HashMap<>();
    public RuntimeException failOnInsert;

    @Override
    public Optional<String> findPasswordHash(UUID accountId) {
      return Optional.ofNullable(hashes.get(accountId));
    }

    @Override
    public void insert(Credential credential) {
      if (failOnInsert != null) {
        throw failOnInsert;
      }
      hashes.put(credential.accountId(), credential.passwordHash());
    }

    @Override
    public void updatePasswordHash(UUID accountId, String passwordHash) {
      hashes.put(accountId, passwordHash);
    }
  }

  /** 事务假实现：快照三个仓储，抛异常就整体还原，模拟回滚。 */
  public static final class FakeTransactionRunner implements TransactionRunner {
    private final FakeUsers users;
    private final FakeAccounts accounts;
    private final FakeCredentials credentials;
    public int transactions;

    public FakeTransactionRunner(
        FakeUsers users, FakeAccounts accounts, FakeCredentials credentials) {
      this.users = users;
      this.accounts = accounts;
      this.credentials = credentials;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
      transactions++;
      var usersBefore = new LinkedHashMap<>(users.byId);
      var accountsBefore = new LinkedHashMap<>(accounts.byId);
      var credentialsBefore = new HashMap<>(credentials.hashes);
      try {
        return work.get();
      } catch (RuntimeException e) {
        users.byId.clear();
        users.byId.putAll(usersBefore);
        accounts.byId.clear();
        accounts.byId.putAll(accountsBefore);
        credentials.hashes.clear();
        credentials.hashes.putAll(credentialsBefore);
        throw e;
      }
    }
  }

  /** 假哈希：{@code fake$<明文>}；记录每次比对，用来断言「先做一次哈希比对」。 */
  public static final class FakePasswordHasher implements PasswordHasher {
    public static final String DUMMY = "fake$\u0000dummy-never-a-password";
    public final List<String> comparedHashes = new ArrayList<>();
    public boolean upgradeNeeded;
    public int hashCalls;

    @Override
    public String hash(String rawPassword) {
      hashCalls++;
      return "fake$" + rawPassword;
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
      comparedHashes.add(passwordHash);
      return passwordHash.equals("fake$" + rawPassword);
    }

    @Override
    public boolean needsUpgrade(String passwordHash) {
      return upgradeNeeded;
    }

    @Override
    public String dummyHash() {
      return DUMMY;
    }
  }

  /** 内存会话存储：一账号一会话，按哈希索引。 */
  public static class FakeSessionStore implements SessionStore {
    public final Map<String, Session> byHash = new LinkedHashMap<>();
    public final List<SessionRef> terminated = new ArrayList<>();
    public final List<Session> touched = new ArrayList<>();

    @Override
    public Optional<SessionRef> create(Session session, String tokenHash) {
      Optional<String> oldHash =
          byHash.entrySet().stream()
              .filter(e -> e.getValue().accountId().equals(session.accountId()))
              .map(Map.Entry::getKey)
              .findFirst();
      Optional<SessionRef> replaced = oldHash.map(h -> byHash.remove(h).ref());
      byHash.put(tokenHash, session);
      return replaced;
    }

    @Override
    public Optional<Session> find(String tokenHash) {
      return Optional.ofNullable(byHash.get(tokenHash));
    }

    @Override
    public Optional<Session> touch(String tokenHash, Session touchedSession) {
      if (!byHash.containsKey(tokenHash)) {
        return Optional.empty();
      }
      byHash.put(tokenHash, touchedSession);
      touched.add(touchedSession);
      return Optional.of(touchedSession);
    }

    @Override
    public boolean terminate(SessionRef ref) {
      terminated.add(ref);
      return byHash.values().removeIf(s -> s.ref().equals(ref));
    }
  }

  /** 内存登录限流：按主体计数，达到阈值后拦截。 */
  public static final class FakeLoginThrottle implements LoginThrottle {
    public final Map<String, Integer> failures = new HashMap<>();
    public final List<String> resets = new ArrayList<>();
    public final int maxFailures;
    public final long retryAfterSeconds;

    public FakeLoginThrottle(int maxFailures, long retryAfterSeconds) {
      this.maxFailures = maxFailures;
      this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public OptionalLong blockedFor(String subject) {
      return failures.getOrDefault(subject, 0) >= maxFailures
          ? OptionalLong.of(retryAfterSeconds)
          : OptionalLong.empty();
    }

    @Override
    public void recordFailure(String subject) {
      failures.merge(subject, 1, Integer::sum);
    }

    @Override
    public void reset(String subject) {
      resets.add(subject);
      failures.remove(subject);
    }
  }
}
