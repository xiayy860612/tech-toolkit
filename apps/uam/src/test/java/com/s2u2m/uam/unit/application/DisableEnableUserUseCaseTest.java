package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.DisableUserUseCase;
import com.s2u2m.uam.application.EnableUserUseCase;
import com.s2u2m.uam.application.GetUserUseCase;
import com.s2u2m.uam.application.UserStatusCommand;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.OperatorContext;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.policy.SessionPolicy;
import com.s2u2m.uam.support.Fakes.FakeAccounts;
import com.s2u2m.uam.support.Fakes.FakeCredentials;
import com.s2u2m.uam.support.Fakes.FakeSessionStore;
import com.s2u2m.uam.support.Fakes.FakeTransactionRunner;
import com.s2u2m.uam.support.Fakes.FakeUserStatusLock;
import com.s2u2m.uam.support.Fakes.FakeUsers;
import com.s2u2m.uam.support.Fakes.RecordingWriteLog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T109：停用 / 启用用例（假端口）——幂等；停用「提交后」才终止会话；写日志（{@code user.disable} / {@code user.enable}）。 */
class DisableEnableUserUseCaseTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
  private static final String CLIENT = "admin-center";
  private static final SessionPolicy POLICY =
      new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12), Duration.ofSeconds(60));

  private final FakeUsers users = new FakeUsers();
  private final FakeAccounts accounts = new FakeAccounts();
  private final FakeCredentials credentials = new FakeCredentials();
  private final FakeTransactionRunner tx = new FakeTransactionRunner(users, accounts, credentials);
  private final RecordingWriteLog writeLog = new RecordingWriteLog();
  private final GetUserUseCase getUser = new GetUserUseCase(users, accounts);

  /** 终止会话时记录：此刻是否仍在事务里，以及目标用户当时的状态（用来断言「提交后」才终止）。 */
  private final List<Boolean> terminatedInsideTransaction = new ArrayList<>();

  private final List<UserStatus> statusAtTermination = new ArrayList<>();
  private final FakeSessionStore sessions =
      new FakeSessionStore() {
        @Override
        public int terminateAllForUser(Collection<UUID> accountIds) {
          terminatedInsideTransaction.add(tx.active);
          users.byId.values().stream()
              .filter(
                  u ->
                      accounts.byId.values().stream()
                          .anyMatch(a -> accountIds.contains(a.id()) && a.userId().equals(u.id())))
              .forEach(u -> statusAtTermination.add(u.status()));
          return super.terminateAllForUser(accountIds);
        }
      };

  private final FakeUserStatusLock lock = new FakeUserStatusLock(() -> tx.active);

  private DisableUserUseCase disable;
  private EnableUserUseCase enable;
  private OperatorContext operator;
  private User operatorUser;
  private User target;

  @BeforeEach
  void setUp() {
    disable = new DisableUserUseCase(users, accounts, sessions, lock, tx, getUser, writeLog);
    enable = new EnableUserUseCase(users, getUser, writeLog);
    operatorUser = newUser("运维甲", UserStatus.ACTIVE);
    target = newUser("张三", UserStatus.ACTIVE);
    operator = new OperatorContext(operatorUser.id(), UUID.randomUUID(), UUID.randomUUID());
  }

  private User newUser(String name, UserStatus status) {
    User created = new User(UUID.randomUUID(), name, status, T0);
    users.insert(created);
    return created;
  }

  private Account newAccount(User owner, String loginName) {
    Account created =
        new Account(
            UUID.randomUUID(),
            owner.id(),
            AccountKind.PASSWORD,
            LoginName.of(loginName),
            AccountStatus.ACTIVE,
            T0);
    accounts.insert(created);
    return created;
  }

  private Session newSession(User owner, Account account) {
    Session session = Session.start(SessionRef.random(), owner, account, T0, POLICY);
    sessions.create(session, "hash-" + session.ref());
    return session;
  }

  private UserStatusCommand command(UUID userId) {
    return new UserStatusCommand(operator, CLIENT, userId);
  }

  private DomainException rejection(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e;
    }
    throw new AssertionError("应抛 DomainException");
  }

  private WriteOperationEvent onlyEvent() {
    assertThat(writeLog.events).hasSize(1);
    return writeLog.events.get(0);
  }

  // ---------------------------------------------------------------- 停用

  @Test
  void disableMarksTheUserDisabledAndReturnsTheDetailWithAccountsUntouched() {
    Account account = newAccount(target, "zhangsan");

    GetUserUseCase.Result result = disable.disable(command(target.id()));

    assertThat(result.user().status()).isEqualTo(UserStatus.DISABLED);
    assertThat(users.byId.get(target.id()).status()).isEqualTo(UserStatus.DISABLED);
    assertThat(result.accounts()).containsExactly(account);
    assertThat(accounts.byId.get(account.id()).status()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(users.byId.get(operatorUser.id()).status()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  void disableTerminatesEverySessionOfEveryAccountOfTheUserAndNothingElse() {
    Account first = newAccount(target, "zhangsan");
    Account second = newAccount(target, "zhangsan-2");
    newAccount(target, "zhangsan-no-session");
    Session s1 = newSession(target, first);
    Session s2 = newSession(target, second);
    Account other = newAccount(operatorUser, "ops");
    Session others = newSession(operatorUser, other);

    disable.disable(command(target.id()));

    assertThat(sessions.byHash.values()).containsExactly(others);
    assertThat(sessions.terminatedForUsers).hasSize(1);
    assertThat(sessions.terminatedForUsers.get(0))
        .containsExactlyInAnyOrder(
            first.id(), second.id(), accounts.findByUserId(target.id()).get(2).id());
    assertThat(sessions.byHash.values()).doesNotContain(s1, s2);
    assertThat(sessions.tombstones).as("停用不留强制下线墓碑").isEmpty();
  }

  @Test
  void disableTerminatesSessionsOnlyAfterTheTransactionCommitted() {
    Account account = newAccount(target, "zhangsan");
    newSession(target, account);

    disable.disable(command(target.id()));

    assertThat(tx.transactions).isEqualTo(1);
    assertThat(terminatedInsideTransaction).containsExactly(false);
    assertThat(statusAtTermination).containsExactly(UserStatus.DISABLED);
  }

  @Test
  void disableTakesTheStatusLockInsideTheTransaction() {
    disable.disable(command(target.id()));

    assertThat(lock.acquiredInsideTransaction).containsExactly(true);
  }

  @Test
  void disableIsIdempotentButStillEnsuresTheSessionsAreGone() {
    Account account = newAccount(target, "zhangsan");
    disable.disable(command(target.id()));
    Session lingering = newSession(target, account);

    GetUserUseCase.Result again = disable.disable(command(target.id()));

    assertThat(again.user().status()).isEqualTo(UserStatus.DISABLED);
    assertThat(sessions.byHash.values()).doesNotContain(lingering);
    assertThat(sessions.terminatedForUsers).hasSize(2);
    assertThat(users.statusUpdates).as("第二次没有真正的状态变更").isEqualTo(1);
    assertThat(writeLog.events)
        .extracting(WriteOperationEvent::outcome)
        .containsExactly(Outcome.SUCCESS, Outcome.SUCCESS);
  }

  @Test
  void disablingYourOwnUserIsRejectedAndChangesNothing() {
    Account account = newAccount(operatorUser, "ops");
    Session own = newSession(operatorUser, account);

    DomainException e = rejection(() -> disable.disable(command(operatorUser.id())));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.CANNOT_DISABLE_SELF);
    assertThat(users.byId.get(operatorUser.id()).status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(sessions.byHash.values()).containsExactly(own);
    assertThat(sessions.terminatedForUsers).isEmpty();
  }

  @Test
  void disablingTheLastActiveUserIsRejectedAndChangesNothing() {
    users.byId.put(
        operatorUser.id(),
        new User(operatorUser.id(), operatorUser.name(), UserStatus.DISABLED, T0));
    Account account = newAccount(target, "zhangsan");
    Session session = newSession(target, account);

    DomainException e = rejection(() -> disable.disable(command(target.id())));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.CANNOT_DISABLE_LAST_ACTIVE_USER);
    assertThat(users.byId.get(target.id()).status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(sessions.byHash.values()).containsExactly(session);
    assertThat(sessions.terminatedForUsers).isEmpty();
  }

  @Test
  void disablingAnUnknownUserIsNotFound() {
    UUID unknown = UUID.randomUUID();

    DomainException e = rejection(() -> disable.disable(command(unknown)));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    assertThat(sessions.terminatedForUsers).isEmpty();
    assertThat(onlyEvent().targetId()).isEqualTo(unknown.toString());
  }

  @Test
  void successWritesAUserDisableEventWithOperatorClientAndTarget() {
    disable.disable(command(target.id()));

    WriteOperationEvent event = onlyEvent();
    assertThat(event.action()).isEqualTo(Action.USER_DISABLE);
    assertThat(event.outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(event.operator()).isEqualTo(operator);
    assertThat(event.clientId()).isEqualTo(CLIENT);
    assertThat(event.targetType()).isEqualTo("user");
    assertThat(event.targetId()).isEqualTo(target.id().toString());
    assertThat(event.reason()).isNull();
  }

  @Test
  void rejectionsWriteARejectedEventWithTheErrorCodeAsReason() {
    rejection(() -> disable.disable(command(operatorUser.id())));
    rejection(() -> disable.disable(command(UUID.randomUUID())));

    assertThat(writeLog.events).hasSize(2);
    assertThat(writeLog.events)
        .allSatisfy(
            event -> {
              assertThat(event.action()).isEqualTo(Action.USER_DISABLE);
              assertThat(event.outcome()).isEqualTo(Outcome.REJECTED);
            });
    assertThat(writeLog.events.get(0).reason()).isEqualTo("cannot_disable_self");
    assertThat(writeLog.events.get(1).reason()).isEqualTo("user_not_found");
  }

  @Test
  void aFailureWhileTerminatingSessionsIsLoggedAsFailedAndRethrown() {
    FakeSessionStore broken =
        new FakeSessionStore() {
          @Override
          public int terminateAllForUser(Collection<UUID> accountIds) {
            throw new IllegalStateException("redis down");
          }
        };
    DisableUserUseCase failing =
        new DisableUserUseCase(users, accounts, broken, lock, tx, getUser, writeLog);

    assertThatThrownBy(() -> failing.disable(command(target.id())))
        .isInstanceOf(IllegalStateException.class);

    WriteOperationEvent event = onlyEvent();
    assertThat(event.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(event.reason()).isEqualTo("internal_error");
    assertThat(users.byId.get(target.id()).status())
        .as("状态变更已提交，重试可幂等地补终止会话")
        .isEqualTo(UserStatus.DISABLED);
  }

  @Test
  void aFailureInsideTheTransactionRollsTheStatusBack() {
    FakeUsers brokenUsers =
        new FakeUsers() {
          @Override
          public boolean updateStatus(UUID id, UserStatus status) {
            super.updateStatus(id, status);
            throw new IllegalStateException("db down");
          }
        };
    brokenUsers.insert(operatorUser);
    brokenUsers.insert(target);
    FakeTransactionRunner brokenTx = new FakeTransactionRunner(brokenUsers, accounts, credentials);
    DisableUserUseCase failing =
        new DisableUserUseCase(
            brokenUsers,
            accounts,
            sessions,
            lock,
            brokenTx,
            new GetUserUseCase(brokenUsers, accounts),
            writeLog);

    assertThatThrownBy(() -> failing.disable(command(target.id())))
        .isInstanceOf(IllegalStateException.class);

    assertThat(brokenUsers.byId.get(target.id()).status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(sessions.terminatedForUsers).as("事务没提交就不终止会话").isEmpty();
    assertThat(onlyEvent().outcome()).isEqualTo(Outcome.FAILED);
  }

  // ---------------------------------------------------------------- 启用

  @Test
  void enableMarksTheUserActiveAndKeepsAccountStatesAsTheyWere() {
    users.byId.put(target.id(), new User(target.id(), target.name(), UserStatus.DISABLED, T0));
    Account account = newAccount(target, "zhangsan");

    GetUserUseCase.Result result = enable.enable(command(target.id()));

    assertThat(result.user().status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(users.byId.get(target.id()).status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(result.accounts()).containsExactly(account);
  }

  @Test
  void enableIsIdempotent() {
    GetUserUseCase.Result result = enable.enable(command(target.id()));

    assertThat(result.user().status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(users.statusUpdates).isZero();
    assertThat(onlyEvent().outcome()).isEqualTo(Outcome.SUCCESS);
  }

  @Test
  void enableWritesAUserEnableEvent() {
    users.byId.put(target.id(), new User(target.id(), target.name(), UserStatus.DISABLED, T0));

    enable.enable(command(target.id()));

    WriteOperationEvent event = onlyEvent();
    assertThat(event.action()).isEqualTo(Action.USER_ENABLE);
    assertThat(event.outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(event.operator()).isEqualTo(operator);
    assertThat(event.clientId()).isEqualTo(CLIENT);
    assertThat(event.targetType()).isEqualTo("user");
    assertThat(event.targetId()).isEqualTo(target.id().toString());
  }

  @Test
  void enablingAnUnknownUserIsNotFoundAndLoggedAsRejected() {
    UUID unknown = UUID.randomUUID();

    DomainException e = rejection(() -> enable.enable(command(unknown)));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    WriteOperationEvent event = onlyEvent();
    assertThat(event.action()).isEqualTo(Action.USER_ENABLE);
    assertThat(event.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(event.reason()).isEqualTo("user_not_found");
    assertThat(event.targetId()).isEqualTo(unknown.toString());
  }

  @Test
  void anUnexpectedFailureWhileEnablingIsLoggedAsFailed() {
    FakeUsers brokenUsers =
        new FakeUsers() {
          @Override
          public boolean updateStatus(UUID id, UserStatus status) {
            throw new IllegalStateException("db down");
          }
        };
    brokenUsers.insert(new User(target.id(), target.name(), UserStatus.DISABLED, T0));
    EnableUserUseCase failing =
        new EnableUserUseCase(brokenUsers, new GetUserUseCase(brokenUsers, accounts), writeLog);

    assertThatThrownBy(() -> failing.enable(command(target.id())))
        .isInstanceOf(IllegalStateException.class);

    WriteOperationEvent event = onlyEvent();
    assertThat(event.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(event.reason()).isEqualTo("internal_error");
  }
}
