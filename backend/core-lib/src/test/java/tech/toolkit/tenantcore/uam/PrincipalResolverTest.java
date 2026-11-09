package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.uam.AuthService.ExchangeResult;
import tech.toolkit.tenantcore.uam.AuthService.LoginResult;
import tech.toolkit.tenantcore.uam.AuthenticatedPrincipal.Source;

/** The request headers decide how a caller is identified (FR-023). */
class PrincipalResolverTest extends PostgresTestBase {

  private final CoreComponents core = core();
  private final PrincipalResolver resolver = core.principalResolver();
  private Bootstrapped bootstrapped;
  private String sessionId;
  private String jwt;

  @BeforeEach
  void prepare() {
    bootstrapped = bootstrap(core, "acme", "admin1");
    LoginResult login = core.auth().login("acme", "admin1", PASSWORD);
    LoginResult changed =
        core.auth().changePassword(login.token(), PASSWORD, "a brand new passphrase");
    sessionId = changed.token();
    ExchangeResult exchanged = core.auth().exchange(sessionId);
    jwt = exchanged.jwt();
  }

  private static void assertUnauthorized(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHORIZED));
  }

  @Test
  void aSessionIdHeaderAloneIsResolvedAsAnOpaqueToken() {
    AuthenticatedPrincipal principal = resolver.resolve(sessionId, null);
    assertThat(principal.source()).isEqualTo(Source.OPAQUE);
    assertThat(principal.tenantUserId()).isEqualTo(bootstrapped.user().tenantUserId());
    assertThat(principal.tenantId()).isEqualTo(bootstrapped.tenant().id());
    assertThat(principal.sid()).startsWith("s_");
  }

  @Test
  void aBearerHeaderAloneIsResolvedAsAnInternalJwt() {
    AuthenticatedPrincipal principal = resolver.resolve(null, "Bearer " + jwt);
    assertThat(principal.source()).isEqualTo(Source.JWT);
    assertThat(principal.tenantUserId()).isEqualTo(bootstrapped.user().tenantUserId());
    assertThat(principal.tenantId()).isEqualTo(bootstrapped.tenant().id());
    assertThat(principal.sid()).isEqualTo(resolver.resolve(sessionId, null).sid());
  }

  @Test
  void blankHeadersCountAsAbsent() {
    assertThat(resolver.resolve(" ", "Bearer " + jwt).source()).isEqualTo(Source.JWT);
    assertThat(resolver.resolve(sessionId, "").source()).isEqualTo(Source.OPAQUE);
  }

  @Test
  void bothHeadersTogetherAreRejected() {
    assertUnauthorized(() -> resolver.resolve(sessionId, "Bearer " + jwt));
  }

  @Test
  void noHeaderIsRejected() {
    assertUnauthorized(() -> resolver.resolve(null, null));
    assertUnauthorized(() -> resolver.resolve("", " "));
  }

  @Test
  void anOpaqueTokenInTheBearerHeaderIsRejected() {
    assertUnauthorized(() -> resolver.resolve(null, "Bearer " + sessionId));
  }

  @Test
  void aJwtInTheSessionIdHeaderIsRejected() {
    assertUnauthorized(() -> resolver.resolve(jwt, null));
  }

  @Test
  void otherAuthorizationSchemesAreRejected() {
    assertUnauthorized(() -> resolver.resolve(null, "Basic " + jwt));
    assertUnauthorized(() -> resolver.resolve(null, jwt));
    assertUnauthorized(() -> resolver.resolve(null, "Bearer "));
  }

  @Test
  void unknownOrRevokedSessionIdsAreRejected() {
    assertUnauthorized(() -> resolver.resolve("tk_unknown", null));
    core.auth().logout(sessionId);
    assertUnauthorized(() -> resolver.resolve(sessionId, null));
  }
}
