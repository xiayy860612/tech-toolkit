package com.s2u2m.uam.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.Credential;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.SessionToken;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.policy.SessionPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** T056 / T057：值对象与实体的行为。 */
class ModelTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00.123456789Z");
  private final SessionPolicy policy =
      new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12), Duration.ofSeconds(60));

  private User user(UserStatus status) {
    return new User(UUID.randomUUID(), "张三", status, T0);
  }

  private Account account(User owner, AccountStatus status) {
    return new Account(
        UUID.randomUUID(), owner.id(), AccountKind.PASSWORD, LoginName.of("Zhang"), status, T0);
  }

  @ParameterizedTest
  @CsvSource({
    "ACTIVE,ACTIVE,true",
    "ACTIVE,DISABLED,false",
    "DISABLED,ACTIVE,false",
    "DELETED,ACTIVE,false",
    "DISABLED,DISABLED,false"
  })
  void canLoginNeedsBothAccountAndOwnerActive(
      AccountStatus accountStatus, UserStatus userStatus, boolean expected) {
    User owner = user(userStatus);

    assertThat(account(owner, accountStatus).canLogin(owner)).isEqualTo(expected);
  }

  @Test
  void canLoginRejectsSomeoneElsesUser() {
    User owner = user(UserStatus.ACTIVE);
    Account account = account(owner, AccountStatus.ACTIVE);

    assertThat(account.canLogin(user(UserStatus.ACTIVE))).isFalse();
  }

  @Test
  void userIsActiveOnlyWhenStatusIsActive() {
    assertThat(user(UserStatus.ACTIVE).isActive()).isTrue();
    assertThat(user(UserStatus.DISABLED).isActive()).isFalse();
  }

  @Test
  void sessionStartsWithMillisecondPrecisionAndPolicyDeadlines() {
    User owner = user(UserStatus.ACTIVE);
    Account account = account(owner, AccountStatus.ACTIVE);

    Session session = Session.start(SessionRef.random(), owner, account, T0, policy);

    Instant now = Instant.parse("2026-10-24T08:00:00.123Z");
    assertThat(session.loginAt()).isEqualTo(now);
    assertThat(session.lastActiveAt()).isEqualTo(now);
    assertThat(session.absoluteExpiresAt()).isEqualTo(now.plus(Duration.ofHours(12)));
    assertThat(session.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    assertThat(session.userId()).isEqualTo(owner.id());
    assertThat(session.userName()).isEqualTo("张三");
    assertThat(session.accountId()).isEqualTo(account.id());
    assertThat(session.loginName()).isEqualTo("Zhang");
    assertThat(session.kind()).isEqualTo(AccountKind.PASSWORD);
  }

  @Test
  void sessionIsActiveStrictlyBeforeItsExpiry() {
    User owner = user(UserStatus.ACTIVE);
    Session session =
        Session.start(SessionRef.random(), owner, account(owner, AccountStatus.ACTIVE), T0, policy);

    assertThat(session.isActive(session.expiresAt().minusMillis(1))).isTrue();
    assertThat(session.isActive(session.expiresAt())).isFalse();
    assertThat(session.isActive(session.expiresAt().plusSeconds(1))).isFalse();
  }

  @Test
  void touchedMovesActivityAndIdleDeadlineButKeepsTheRest() {
    User owner = user(UserStatus.ACTIVE);
    Session session =
        Session.start(SessionRef.random(), owner, account(owner, AccountStatus.ACTIVE), T0, policy);
    Instant later = session.loginAt().plus(Duration.ofMinutes(5));

    Session touched = session.touched(later, policy);

    assertThat(touched.lastActiveAt()).isEqualTo(later);
    assertThat(touched.expiresAt()).isEqualTo(later.plus(Duration.ofMinutes(30)));
    assertThat(touched.loginAt()).isEqualTo(session.loginAt());
    assertThat(touched.absoluteExpiresAt()).isEqualTo(session.absoluteExpiresAt());
    assertThat(touched.ref()).isEqualTo(session.ref());
  }

  @Test
  void sessionRefsAreRandomUuidsThatRoundTrip() {
    SessionRef ref = SessionRef.random();

    assertThat(SessionRef.parse(ref.toString())).isEqualTo(ref);
    assertThat(SessionRef.random()).isNotEqualTo(ref);
  }

  @Test
  void sessionTokenIsHighEntropyBase64UrlAndOnlyItsHashIsDerivable() {
    SessionToken token = SessionToken.generate();

    assertThat(token.id()).matches("[A-Za-z0-9_-]{43}");
    assertThat(token.hash()).matches("[0-9a-f]{64}");
    assertThat(SessionToken.hashOf(token.id())).isEqualTo(token.hash());
    assertThat(SessionToken.generate().id()).isNotEqualTo(token.id());
    assertThat(token.toString()).doesNotContain(token.id());
  }

  @Test
  void credentialNeverPrintsItsHash() {
    assertThat(new Credential(UUID.randomUUID(), "{argon2}$secret-hash").toString())
        .doesNotContain("secret-hash");
  }
}
