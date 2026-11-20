package tech.toolkit.tenantcore.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.identity.Account;
import tech.toolkit.tenantcore.identity.AccountRepository;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.identity.Status;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.PasswordHasher;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.MutableClock;
import tech.toolkit.tenantcore.support.TestAccounts;

class LoginServiceTest extends AbstractPostgresIT {

  private static final String PW = "Str0ng-Passw0rd";

  @Autowired LoginService login;
  @Autowired TestAccounts accountsFixture;
  @Autowired AccountRepository accounts;
  @Autowired AppUserRepository users;
  @Autowired TransactionTemplate tx;
  @Autowired MutableClock clock;
  @MockitoSpyBean PasswordHasher hasher;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

  @BeforeEach
  void resetClock() {
    clock.reset();
  }

  private LoginService.LoggedIn loginAs0(String username, String password) {
    return TenantContext.callAs(0L, () -> login.login(username, password, "203.0.113.20", "UA"));
  }

  private void expectInvalid(String username, String password) {
    assertThatThrownBy(() -> loginAs0(username, password))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
  }

  private Account reload(Account a) {
    return TenantContext.callAs(
        0L, () -> tx.execute(s -> accounts.findById(a.getId()).orElseThrow()));
  }

  @Test
  void successfulLoginCreatesASessionClearsCountersAndAudits() {
    var acc = accountsFixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, true);
    LoginService.LoggedIn in = loginAs0(acc.username().toUpperCase(), PW); // 大小写不敏感

    assertThat(in.token()).isNotBlank();
    assertThat(in.mustChangePassword()).isTrue();
    assertThat(in.session().getUserId()).isEqualTo(acc.user().getId());
    Account after = reload(acc.account());
    assertThat(after.getFailedAttempts()).isZero();
    assertThat(after.getLastLoginAt()).isNotNull();
    assertThat(
            owner.queryForObject(
                "select count(*) from audit_log where action='LOGIN_SUCCESS' and actor_account_id=?",
                Integer.class,
                acc.account().getId()))
        .isEqualTo(1);
  }

  @Test
  void allFailureKindsLookTheSameToTheCaller() {
    var wrongPw = accountsFixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var accountOff = accountsFixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var userOff = accountsFixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var locked = accountsFixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s -> {
                  accounts
                      .findById(accountOff.account().getId())
                      .orElseThrow()
                      .setStatus(Status.DISABLED);
                  users.findById(userOff.user().getId()).orElseThrow().setStatus(Status.DISABLED);
                  accounts
                      .findById(locked.account().getId())
                      .orElseThrow()
                      .setLockedUntil(clock.instant().plus(Duration.ofMinutes(5)));
                }));

    expectInvalid("nobody-" + TestAccounts.uniqueName(), PW);
    expectInvalid(wrongPw.username(), "wrong-password");
    expectInvalid(accountOff.username(), PW);
    expectInvalid(userOff.username(), PW);
    expectInvalid(locked.username(), PW); // 锁定期间即使密码正确也拒绝
  }

  @Test
  void locksTheAccountAfterFiveFailuresForFifteenMinutes() {
    var acc = accountsFixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    for (int i = 0; i < 4; i++) {
      expectInvalid(acc.username(), "bad-" + i);
    }
    assertThat(reload(acc.account()).getFailedAttempts()).isEqualTo(4);

    expectInvalid(acc.username(), "bad-5"); // 第 5 次 → 锁定
    Account locked = reload(acc.account());
    assertThat(locked.getLockedUntil()).isAfter(clock.instant().plus(Duration.ofMinutes(14)));
    assertThat(locked.getLockedUntil()).isBefore(clock.instant().plus(Duration.ofMinutes(16)));
    assertThat(
            owner.queryForObject(
                "select count(*) from audit_log where action='ACCOUNT_LOCKED' and actor_account_id=?",
                Integer.class,
                acc.account().getId()))
        .isEqualTo(1);

    expectInvalid(acc.username(), PW); // 仍在锁定期
    clock.advance(Duration.ofMinutes(16));
    assertThat(loginAs0(acc.username(), PW).token()).isNotBlank(); // 到期自动解锁
  }

  @Test
  void aSuccessfulLoginResetsTheFailureCounter() {
    var acc = accountsFixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    expectInvalid(acc.username(), "bad");
    expectInvalid(acc.username(), "bad");
    loginAs0(acc.username(), PW);
    assertThat(reload(acc.account()).getFailedAttempts()).isZero();
  }

  @Test
  void unknownUsernamesStillSpendTheSameHashingWork() {
    expectInvalid("ghost-" + TestAccounts.uniqueName(), PW);
    verify(hasher, atLeastOnce()).dummyMatch(PW);
  }

  @Test
  void lookupIsLimitedToTheTenantOfTheCaller() {
    var acc = accountsFixture.create(1, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    expectInvalid(acc.username(), PW); // 以租户 0 登录租户 1 的用户 → 找不到
    assertThat(
            TenantContext.callAs(1L, () -> login.login(acc.username(), PW, "203.0.113.20", "UA"))
                .token())
        .isNotBlank();
  }

  @Test
  void failuresAreAuditedWithTheTrueReasonButNeverWithThePassword() {
    var acc = accountsFixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    expectInvalid(acc.username(), "definitely-wrong-Passw0rd");
    List<String> reasons =
        owner.queryForList(
            "select reason from audit_log where action='LOGIN_FAILURE' and actor_account_id=?",
            String.class,
            acc.account().getId());
    assertThat(reasons).containsExactly("BAD_PASSWORD");
    assertThat(
            owner.queryForObject(
                "select count(*) from audit_log where detail::text like '%definitely-wrong%'",
                Integer.class))
        .isZero();
  }
}
