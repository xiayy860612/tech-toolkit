package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.uam.AuthService.LoginResult;

class ChangePasswordIT extends PostgresTestBase {

  private static final String NEW_PASSWORD = "a brand new passphrase";

  private final CoreComponents core = core();
  private final AuthService auth = core.auth();
  private final JdbcClient owner = JdbcClient.create(ownerDataSource());

  private static void assertCode(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(expected));
  }

  @Test
  void aWrongCurrentPasswordIsInvalidCredentialsAndNothingChanges() {
    bootstrap(core, "acme", "admin1");
    LoginResult login = auth.login("acme", "admin1", PASSWORD);
    assertCode(
        () -> auth.changePassword(login.token(), "not the current one", NEW_PASSWORD),
        ErrorCode.INVALID_CREDENTIALS);
    assertThat(core.opaqueTokens().authenticate(login.token())).isPresent();
  }

  @Test
  void aBadTokenIsUnauthorized() {
    bootstrap(core, "acme", "admin1");
    assertCode(
        () -> auth.changePassword("tk_bogus", PASSWORD, NEW_PASSWORD), ErrorCode.UNAUTHORIZED);
  }

  @Test
  void aWeakNewPasswordIsRejected() {
    bootstrap(core, "acme", "admin1");
    LoginResult login = auth.login("acme", "admin1", PASSWORD);
    assertCode(
        () -> auth.changePassword(login.token(), PASSWORD, "short"), ErrorCode.PASSWORD_POLICY);
    assertCode(
        () -> auth.changePassword(login.token(), PASSWORD, "admin1"), ErrorCode.PASSWORD_POLICY);
  }

  @Test
  void changingClearsTheFlagRotatesTheTokenAndSwitchesThePassword() {
    bootstrap(core, "acme", "admin1");
    LoginResult first = auth.login("acme", "admin1", PASSWORD);
    assertThat(first.mustChangePassword()).isTrue();

    LoginResult changed = auth.changePassword(first.token(), PASSWORD, NEW_PASSWORD);

    assertThat(changed.mustChangePassword()).isFalse();
    assertThat(changed.token()).isNotEqualTo(first.token());
    assertThat(core.opaqueTokens().authenticate(first.token())).isEmpty();
    assertThat(core.opaqueTokens().authenticate(changed.token())).isPresent();
    assertThat(
            owner
                .sql("SELECT must_change_password FROM uam_credential_password")
                .query(Boolean.class)
                .single())
        .isFalse();
    assertCode(() -> auth.login("acme", "admin1", PASSWORD), ErrorCode.INVALID_CREDENTIALS);
    assertThat(auth.login("acme", "admin1", NEW_PASSWORD).mustChangePassword()).isFalse();
    assertThat(auditOutcomes("password.change")).containsExactly("SUCCESS");
  }

  @Test
  void aFailedAttemptToChangeCountsTowardsLockout() {
    bootstrap(core, "acme", "admin1");
    LoginResult login = auth.login("acme", "admin1", PASSWORD);
    for (int i = 0; i < 5; i++) {
      assertCode(
          () -> auth.changePassword(login.token(), "wrong password 1234", NEW_PASSWORD),
          ErrorCode.INVALID_CREDENTIALS);
    }
    assertCode(() -> auth.login("acme", "admin1", PASSWORD), ErrorCode.INVALID_CREDENTIALS);
  }
}
