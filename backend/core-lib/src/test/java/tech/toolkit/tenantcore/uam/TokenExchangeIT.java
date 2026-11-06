package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.uam.AuthService.ExchangeResult;
import tech.toolkit.tenantcore.uam.AuthService.LoginResult;
import tech.toolkit.tenantcore.uam.AuthService.Me;

class TokenExchangeIT extends PostgresTestBase {

  private final MutableClock clock = new MutableClock();
  private final CoreComponents core = core(clock);

  private static void assertCode(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(expected));
  }

  /** A Session that already changed its initial password (so it may be exchanged). */
  private LoginResult readySession(Bootstrapped b) {
    LoginResult login = core.auth().login("acme", "admin1", PASSWORD);
    return core.auth().changePassword(login.token(), PASSWORD, "a brand new passphrase");
  }

  @Test
  void exchangesAValidSessionForAShortLivedJwtOfTheSameSession() {
    Bootstrapped b = bootstrap(core, "acme", "admin1");
    LoginResult session = readySession(b);

    ExchangeResult exchanged = core.auth().exchange(session.token());

    var principal = core.jwt().verify(exchanged.jwt());
    assertThat(principal.tenantUserId()).isEqualTo(b.user().tenantUserId());
    assertThat(principal.tenantId()).isEqualTo(b.tenant().id());
    assertThat(principal.sid())
        .isEqualTo(core.principalResolver().resolve(session.token(), null).sid());
    assertThat(exchanged.expiresAt()).isEqualTo(clock.instant().plusSeconds(120));
    assertThat(auditOutcomes("token.exchange")).containsExactly("SUCCESS");
  }

  @Test
  void aSessionThatMustChangeItsPasswordCannotBeExchanged() {
    bootstrap(core, "acme", "admin1");
    LoginResult login = core.auth().login("acme", "admin1", PASSWORD);
    assertThat(login.mustChangePassword()).isTrue();
    assertCode(() -> core.auth().exchange(login.token()), ErrorCode.PASSWORD_CHANGE_REQUIRED);
    assertThat(auditOutcomes("token.exchange")).isEmpty();
  }

  @Test
  void unknownExpiredAndRevokedSessionsCannotBeExchanged() {
    Bootstrapped b = bootstrap(core, "acme", "admin1");
    LoginResult session = readySession(b);
    assertCode(() -> core.auth().exchange("tk_unknown"), ErrorCode.UNAUTHORIZED);
    assertCode(() -> core.auth().exchange(null), ErrorCode.UNAUTHORIZED);

    core.auth().logout(session.token());
    assertCode(() -> core.auth().exchange(session.token()), ErrorCode.UNAUTHORIZED);

    LoginResult second = core.auth().login("acme", "admin1", "a brand new passphrase");
    clock.advance(Duration.ofMinutes(31));
    assertCode(() -> core.auth().exchange(second.token()), ErrorCode.UNAUTHORIZED);
  }

  @Test
  void anAlreadyIssuedJwtSurvivesLogoutUntilItExpires() {
    Bootstrapped b = bootstrap(core, "acme", "admin1");
    LoginResult session = readySession(b);
    String jwt = core.auth().exchange(session.token()).jwt();

    core.auth().logout(session.token());

    assertThat(core.jwt().verify(jwt).tenantUserId()).isEqualTo(b.user().tenantUserId());
    clock.advance(Duration.ofSeconds(121));
    assertCode(() -> core.jwt().verify(jwt), ErrorCode.UNAUTHORIZED);
  }

  @Test
  void meDescribesTheCallerForBothKindsOfCredential() {
    Bootstrapped b = bootstrap(core, "acme", "admin1");
    LoginResult session = readySession(b);
    String jwt = core.auth().exchange(session.token()).jwt();

    Me viaSession = core.auth().me(core.principalResolver().resolve(session.token(), null));
    Me viaJwt = core.auth().me(core.principalResolver().resolve(null, "Bearer " + jwt));

    assertThat(viaSession).isEqualTo(viaJwt);
    assertThat(viaJwt.tenantUserId()).isEqualTo(b.user().tenantUserId());
    assertThat(viaJwt.displayName()).isEqualTo("User admin1");
    assertThat(viaJwt.accountUsername()).isEqualTo("admin1");
    assertThat(viaJwt.tenantSlug()).isEqualTo("acme");
    assertThat(viaJwt.mustChangePassword()).isFalse();

    // still answerable through the JWT after the session was revoked (display only)
    var jwtPrincipal = core.principalResolver().resolve(null, "Bearer " + jwt);
    core.auth().logout(session.token());
    assertThat(core.auth().me(jwtPrincipal)).isEqualTo(viaJwt);
  }

  @Test
  void meReportsThatThePasswordMustBeChanged() {
    bootstrap(core, "acme", "admin1");
    LoginResult login = core.auth().login("acme", "admin1", PASSWORD);
    var principal = core.principalResolver().resolve(login.token(), null);
    assertThat(core.auth().me(principal).mustChangePassword()).isTrue();
    assertThat(core.auth().passwordChangeRequired(principal)).isTrue();
  }

  @Test
  void loginAndLogoutAreAudited() {
    bootstrap(core, "acme", "admin1");
    assertCode(
        () -> core.auth().login("acme", "admin1", "wrong password 1234"),
        ErrorCode.INVALID_CREDENTIALS);
    LoginResult login = core.auth().login("acme", "admin1", PASSWORD);
    core.auth().logout(login.token());
    assertThat(auditOutcomes("login.failure")).containsExactly("FAILURE");
    assertThat(auditOutcomes("login.success")).containsExactly("SUCCESS");
    assertThat(auditOutcomes("logout")).containsExactly("SUCCESS");
  }
}
