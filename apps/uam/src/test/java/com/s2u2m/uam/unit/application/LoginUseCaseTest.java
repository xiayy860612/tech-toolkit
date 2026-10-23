package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.LoginCommand;
import com.s2u2m.uam.application.LoginResult;
import com.s2u2m.uam.application.LoginUseCase;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionToken;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.policy.SessionPolicy;
import com.s2u2m.uam.support.Fakes.FakeAccounts;
import com.s2u2m.uam.support.Fakes.FakeClock;
import com.s2u2m.uam.support.Fakes.FakeCredentials;
import com.s2u2m.uam.support.Fakes.FakeLoginThrottle;
import com.s2u2m.uam.support.Fakes.FakePasswordHasher;
import com.s2u2m.uam.support.Fakes.FakeSessionStore;
import com.s2u2m.uam.support.Fakes.FakeUsers;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T046：登录用例（假端口）。 */
class LoginUseCaseTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
  private static final String IP = "203.0.113.7";
  private static final String PASSWORD = "Passw0rd-fake-1";

  private final FakeClock clock = new FakeClock(T0);
  private final FakeUsers users = new FakeUsers();
  private final FakeAccounts accounts = new FakeAccounts();
  private final FakeCredentials credentials = new FakeCredentials();
  private final FakePasswordHasher hasher = new FakePasswordHasher();
  private final FakeSessionStore sessions = new FakeSessionStore();
  private final FakeLoginThrottle throttle = new FakeLoginThrottle(5, 900);
  private final SessionPolicy sessionPolicy =
      new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12), Duration.ofSeconds(60));
  private final LoginThrottlePolicy throttlePolicy =
      new LoginThrottlePolicy(5, Duration.ofMinutes(15));

  private LoginUseCase useCase;
  private User user;
  private Account account;

  @BeforeEach
  void setUp() {
    useCase =
        new LoginUseCase(
            users,
            accounts,
            credentials,
            hasher,
            sessions,
            throttle,
            throttlePolicy,
            sessionPolicy,
            clock);
    user = newUser(UserStatus.ACTIVE);
    account = newAccount(user, "Alice", AccountStatus.ACTIVE);
  }

  private User newUser(UserStatus status) {
    User created = new User(UUID.randomUUID(), "爱丽丝", status, T0);
    users.insert(created);
    return created;
  }

  private Account newAccount(User owner, String loginName, AccountStatus status) {
    Account created =
        new Account(
            UUID.randomUUID(),
            owner.id(),
            AccountKind.PASSWORD,
            LoginName.of(loginName),
            status,
            T0);
    accounts.insert(created);
    credentials.hashes.put(created.id(), hasher.hash(PASSWORD));
    return created;
  }

  private LoginCommand login(String accountName, String password) {
    return new LoginCommand(accountName, password, IP);
  }

  private void assertInvalidCredentials(LoginCommand command) {
    assertThatThrownBy(() -> useCase.login(command))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
  }

  @Test
  void successIssuesASessionAndReturnsItsData() {
    LoginResult result = useCase.login(login("alice", PASSWORD));

    Session session = result.session();
    assertThat(session.userId()).isEqualTo(user.id());
    assertThat(session.userName()).isEqualTo("爱丽丝");
    assertThat(session.accountId()).isEqualTo(account.id());
    assertThat(session.loginName()).isEqualTo("Alice");
    assertThat(session.kind()).isEqualTo(AccountKind.PASSWORD);
    assertThat(session.loginAt()).isEqualTo(T0);
    assertThat(session.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
    assertThat(session.absoluteExpiresAt()).isEqualTo(T0.plus(Duration.ofHours(12)));
    assertThat(result.maxAgeSeconds()).isEqualTo(Duration.ofHours(12).toSeconds());
    assertThat(result.sessionId()).hasSizeGreaterThanOrEqualTo(43).doesNotContain("=", "+", "/");
  }

  @Test
  void onlyTheSha256OfTheSessionIdIsStored() {
    LoginResult result = useCase.login(login("alice", PASSWORD));

    assertThat(sessions.byHash).containsOnlyKeys(SessionToken.hashOf(result.sessionId()));
    assertThat(sessions.byHash.keySet()).doesNotContain(result.sessionId());
  }

  @Test
  void sessionIdsAreRandomPerLogin() {
    String first = useCase.login(login("alice", PASSWORD)).sessionId();
    String second = useCase.login(login("alice", PASSWORD)).sessionId();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void loginNameIsCaseInsensitive() {
    assertThat(useCase.login(login("ALICE", PASSWORD)).session().accountId())
        .isEqualTo(account.id());
  }

  @Test
  void secondLoginOfTheSameAccountReplacesTheFirstSession() {
    LoginResult first = useCase.login(login("alice", PASSWORD));
    LoginResult second = useCase.login(login("alice", PASSWORD));

    assertThat(sessions.byHash).hasSize(1);
    assertThat(sessions.byHash).containsKey(SessionToken.hashOf(second.sessionId()));
    assertThat(sessions.byHash).doesNotContainKey(SessionToken.hashOf(first.sessionId()));
  }

  @Test
  void unknownAccountIsInvalidCredentialsAndStillComparesAHash() {
    assertInvalidCredentials(login("nobody", PASSWORD));

    assertThat(hasher.comparedHashes).containsExactly(FakePasswordHasher.DUMMY);
    assertThat(sessions.byHash).isEmpty();
  }

  @Test
  void wrongPasswordIsInvalidCredentialsAndComparesTheRealHash() {
    assertInvalidCredentials(login("alice", "Wrong-password-1"));

    assertThat(hasher.comparedHashes).containsExactly(credentials.hashes.get(account.id()));
    assertThat(sessions.byHash).isEmpty();
  }

  @Test
  void disabledAccountIsInvalidCredentialsEvenWithTheRightPasswordAndStillComparesAHash() {
    Account disabled = newAccount(user, "Disabled", AccountStatus.DISABLED);

    assertInvalidCredentials(login("disabled", PASSWORD));

    assertThat(hasher.comparedHashes).containsExactly(credentials.hashes.get(disabled.id()));
    assertThat(sessions.byHash).isEmpty();
  }

  @Test
  void deletedAccountCannotLogin() {
    newAccount(user, "Gone", AccountStatus.DELETED);

    assertInvalidCredentials(login("gone", PASSWORD));
  }

  @Test
  void disabledOwnerIsInvalidCredentialsAndStillComparesAHash() {
    User disabledUser = newUser(UserStatus.DISABLED);
    Account owned = newAccount(disabledUser, "Owned", AccountStatus.ACTIVE);

    assertInvalidCredentials(login("owned", PASSWORD));

    assertThat(hasher.comparedHashes).containsExactly(credentials.hashes.get(owned.id()));
    assertThat(sessions.byHash).isEmpty();
  }

  @Test
  void accountWithoutACredentialIsTreatedLikeAnUnknownAccount() {
    credentials.hashes.remove(account.id());

    assertInvalidCredentials(login("alice", PASSWORD));

    assertThat(hasher.comparedHashes).containsExactly(FakePasswordHasher.DUMMY);
  }

  @Test
  void ownerDisabledWhileLoggingInTerminatesTheFreshSessionAndRejects() {
    FakeSessionStore racing =
        new FakeSessionStore() {
          @Override
          public Optional<com.s2u2m.uam.domain.model.SessionRef> create(
              Session session, String tokenHash) {
            Optional<com.s2u2m.uam.domain.model.SessionRef> replaced =
                super.create(session, tokenHash);
            users.byId.put(
                user.id(), new User(user.id(), user.name(), UserStatus.DISABLED, user.createdAt()));
            return replaced;
          }
        };
    LoginUseCase racingUseCase =
        new LoginUseCase(
            users,
            accounts,
            credentials,
            hasher,
            racing,
            throttle,
            throttlePolicy,
            sessionPolicy,
            clock);

    assertThatThrownBy(() -> racingUseCase.login(login("alice", PASSWORD)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

    assertThat(racing.byHash).isEmpty();
    assertThat(racing.terminated).hasSize(1);
    String subject = throttlePolicy.subject(LoginName.of("alice"), IP);
    assertThat(throttle.failures.get(subject)).isEqualTo(1);
  }

  @Test
  void failuresAreCountedPerAccountAndSourceAndSuccessResetsThem() {
    String subject = throttlePolicy.subject(LoginName.of("alice"), IP);

    assertInvalidCredentials(login("alice", "Wrong-password-1"));
    assertInvalidCredentials(login("alice", "Wrong-password-2"));
    assertThat(throttle.failures.get(subject)).isEqualTo(2);

    useCase.login(login("alice", PASSWORD));

    assertThat(throttle.failures).doesNotContainKey(subject);
    assertThat(throttle.resets).containsExactly(subject);
  }

  @Test
  void unknownAccountsAreCountedToo() {
    assertInvalidCredentials(login("nobody", PASSWORD));

    assertThat(throttle.failures.get(throttlePolicy.subject(LoginName.of("nobody"), IP)))
        .isEqualTo(1);
  }

  @Test
  void afterTheThresholdEvenTheRightPasswordGets429WithoutTouchingCredentials() {
    for (int i = 0; i < 5; i++) {
      assertInvalidCredentials(login("alice", "Wrong-password-1"));
    }
    hasher.comparedHashes.clear();

    assertThatThrownBy(() -> useCase.login(login("alice", PASSWORD)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.THROTTLED);
              assertThat(e.retryAfterSeconds()).hasValue(900);
            });

    assertThat(hasher.comparedHashes).isEmpty();
    assertThat(sessions.byHash).isEmpty();
  }

  @Test
  void anotherSourceIsNotBlocked() {
    for (int i = 0; i < 5; i++) {
      assertInvalidCredentials(login("alice", "Wrong-password-1"));
    }

    LoginResult result = useCase.login(new LoginCommand("alice", PASSWORD, "198.51.100.9"));

    assertThat(result.session().accountId()).isEqualTo(account.id());
  }

  @Test
  void outdatedHashIsUpgradedAfterASuccessfulLogin() {
    hasher.upgradeNeeded = true;

    useCase.login(login("alice", PASSWORD));

    assertThat(credentials.hashes.get(account.id())).isEqualTo("fake$" + PASSWORD);
    assertThat(hasher.hashCalls).isEqualTo(2); // 造数一次 + 升级一次
  }

  @Test
  void currentHashIsLeftAloneAfterASuccessfulLogin() {
    hasher.upgradeNeeded = false;
    int before = hasher.hashCalls;

    useCase.login(login("alice", PASSWORD));

    assertThat(hasher.hashCalls).isEqualTo(before);
  }

  @Test
  void commandAndResultNeverPrintSecrets() {
    LoginResult result = useCase.login(login("alice", PASSWORD));

    assertThat(login("alice", PASSWORD).toString()).doesNotContain(PASSWORD);
    assertThat(result.toString()).doesNotContain(result.sessionId());
  }
}
