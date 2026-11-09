package tech.toolkit.tenantcore.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;

/** contracts/tenant-core-api.yaml: status codes, bodies and the two kinds of request credential. */
class AuthApiContractIT extends ApiTestBase {

  private static final Map<String, Object> CHANGE =
      Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD);

  @Test
  void loginReturnsAnOpaqueTokenThatMustBeChangedFirst() {
    bootstrap(core(), "acme", "admin1");
    Resp resp = login("acme", "Admin1", PASSWORD);
    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.json().get("token").asText()).startsWith("tk_");
    assertThat(resp.json().get("expiresAt").asText()).isNotBlank();
    assertThat(resp.json().get("mustChangePassword").asBoolean()).isTrue();
    assertThat(resp.raw()).doesNotContain("argon2");
  }

  @Test
  void failedLoginsAreUniform() {
    bootstrap(core(), "acme", "admin1");
    Resp wrong = login("acme", "admin1", "wrong password 1234");
    Resp unknown = login("acme", "nobody", "wrong password 1234");
    Resp noTenant = login("nope", "admin1", "wrong password 1234");
    for (Resp r : new Resp[] {wrong, unknown, noTenant}) {
      assertThat(r.status()).isEqualTo(401);
      assertThat(r.json().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
      assertThat(r.json().get("message").asText()).isEqualTo(wrong.json().get("message").asText());
    }
  }

  @Test
  void missingFieldsAreAValidationError() {
    Resp resp = call(HttpMethod.POST, "/v1/auth/login", Map.of(), Map.of("tenantSlug", "acme"));
    assertThat(resp.status()).isEqualTo(400);
    assertThat(resp.json().get("code").asText()).isEqualTo("VALIDATION");
  }

  @Test
  void moduleNotSubscribedIsOnlyRevealedAfterTheRightPassword() {
    bootstrap(core(), "acme", "admin1");
    JdbcClient.create(ownerDataSource()).sql("DELETE FROM core_module_subscription").update();
    assertThat(login("acme", "admin1", "wrong password 1234").status()).isEqualTo(401);
    Resp resp = login("acme", "admin1", PASSWORD);
    assertThat(resp.status()).isEqualTo(403);
    assertThat(resp.json().get("code").asText()).isEqualTo("MODULE_NOT_SUBSCRIBED");
  }

  @Test
  void aSessionThatMustChangeItsPasswordCanOnlyChangeLogOutOrAskWhoItIs() {
    bootstrap(core(), "acme", "admin1");
    String initial = login("acme", "admin1", PASSWORD).json().get("token").asText();

    for (String path : new String[] {"/v1/tenants", "/v1/users", "/v1/module-subscriptions"}) {
      Resp resp = call(HttpMethod.GET, path, session(initial), null);
      assertThat(resp.status()).as(path).isEqualTo(403);
      assertThat(resp.json().get("code").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    }
    Resp exchange = call(HttpMethod.POST, "/v1/auth/exchange", session(initial), null);
    assertThat(exchange.status()).isEqualTo(403);
    assertThat(exchange.json().get("code").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");

    Resp me = call(HttpMethod.GET, "/v1/auth/me", session(initial), null);
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.json().get("mustChangePassword").asBoolean()).isTrue();
  }

  @Test
  void changePasswordRotatesTheTokenAndValidatesInput() {
    bootstrap(core(), "acme", "admin1");
    String initial = login("acme", "admin1", PASSWORD).json().get("token").asText();

    Resp wrongCurrent =
        call(
            HttpMethod.POST,
            "/v1/auth/change-password",
            session(initial),
            Map.of("currentPassword", "not the current one", "newPassword", NEW_PASSWORD));
    assertThat(wrongCurrent.status()).isEqualTo(401);
    assertThat(wrongCurrent.json().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");

    Resp weak =
        call(
            HttpMethod.POST,
            "/v1/auth/change-password",
            session(initial),
            Map.of("currentPassword", PASSWORD, "newPassword", "short"));
    assertThat(weak.status()).isEqualTo(400);
    assertThat(weak.json().get("code").asText()).isEqualTo("PASSWORD_POLICY");

    Resp changed = call(HttpMethod.POST, "/v1/auth/change-password", session(initial), CHANGE);
    assertThat(changed.status()).isEqualTo(200);
    assertThat(changed.json().get("mustChangePassword").asBoolean()).isFalse();
    assertThat(changed.json().get("token").asText()).isNotEqualTo(initial);
    assertThat(call(HttpMethod.GET, "/v1/auth/me", session(initial), null).status()).isEqualTo(401);
  }

  @Test
  void meDescribesTheCaller() {
    String sessionId = readySession("acme", "admin1");
    Resp me = call(HttpMethod.GET, "/v1/auth/me", session(sessionId), null);
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.json().get("tenantUserId").asText()).startsWith("tu_");
    assertThat(me.json().get("displayName").asText()).isEqualTo("User admin1");
    assertThat(me.json().get("accountUsername").asText()).isEqualTo("admin1");
    assertThat(me.json().get("tenantSlug").asText()).isEqualTo("acme");
    assertThat(me.json().get("mustChangePassword").asBoolean()).isFalse();
  }

  @Test
  void exchangeGivesAJwtVerifiableWithThePublishedKey() throws Exception {
    String sessionId = readySession("acme", "admin1");
    Resp exchange = call(HttpMethod.POST, "/v1/auth/exchange", session(sessionId), null);
    assertThat(exchange.status()).isEqualTo(200);
    assertThat(exchange.json().get("expiresAt").asText()).isNotBlank();
    String jwt = exchange.json().get("jwt").asText();

    Resp jwks = call(HttpMethod.GET, "/.well-known/jwks.json", Map.of(), null);
    assertThat(jwks.status()).isEqualTo(200);
    assertThat(jwks.raw()).doesNotContain("\"d\"");
    ECKey published = (ECKey) JWKSet.parse(jwks.raw()).getKeys().get(0);
    SignedJWT parsed = SignedJWT.parse(jwt);
    assertThat(parsed.getHeader().getKeyID()).isEqualTo(published.getKeyID());
    assertThat(parsed.verify(new ECDSAVerifier(published))).isTrue();
    assertThat(parsed.getJWTClaimsSet().getSubject()).startsWith("tu_");
  }

  @Test
  void bothKindsOfCredentialAreAcceptedForMeAndLists() {
    String sessionId = readySession("acme", "admin1");
    String jwt = exchange(sessionId);
    for (String path :
        new String[] {"/v1/auth/me", "/v1/tenants", "/v1/users", "/v1/module-subscriptions"}) {
      assertThat(call(HttpMethod.GET, path, session(sessionId), null).status())
          .as(path)
          .isEqualTo(200);
      assertThat(call(HttpMethod.GET, path, bearer(jwt), null).status()).as(path).isEqualTo(200);
    }
    assertThat(call(HttpMethod.GET, "/v1/auth/me", session(sessionId), null).json())
        .isEqualTo(call(HttpMethod.GET, "/v1/auth/me", bearer(jwt), null).json());
  }

  @Test
  void sessionOnlyEndpointsRejectAnInternalJwt() {
    String sessionId = readySession("acme", "admin1");
    String jwt = exchange(sessionId);
    assertThat(call(HttpMethod.POST, "/v1/auth/logout", bearer(jwt), null).status()).isEqualTo(401);
    assertThat(call(HttpMethod.POST, "/v1/auth/exchange", bearer(jwt), null).status())
        .isEqualTo(401);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/v1/auth/change-password",
                    bearer(jwt),
                    Map.of(
                        "currentPassword", NEW_PASSWORD, "newPassword", "yet another passphrase"))
                .status())
        .isEqualTo(401);
  }

  @Test
  void wrongHeaderCombinationsAreUnauthorized() {
    String sessionId = readySession("acme", "admin1");
    String jwt = exchange(sessionId);
    assertThat(call(HttpMethod.GET, "/v1/tenants", Map.of(), null).status()).isEqualTo(401);
    assertThat(
            call(
                    HttpMethod.GET,
                    "/v1/tenants",
                    Map.of("X-Session-Id", sessionId, "Authorization", "Bearer " + jwt),
                    null)
                .status())
        .isEqualTo(401);
    assertThat(call(HttpMethod.GET, "/v1/tenants", bearer(sessionId), null).status())
        .isEqualTo(401);
    assertThat(call(HttpMethod.GET, "/v1/tenants", session(jwt), null).status()).isEqualTo(401);
    Resp unauthorized = call(HttpMethod.GET, "/v1/tenants", Map.of(), null);
    assertThat(unauthorized.json().get("code").asText()).isEqualTo("UNAUTHORIZED");
  }

  @Test
  void logoutRevokesTheSessionAndStopsFurtherExchange() {
    String sessionId = readySession("acme", "admin1");
    assertThat(call(HttpMethod.POST, "/v1/auth/logout", session(sessionId), null).status())
        .isEqualTo(204);
    assertThat(call(HttpMethod.GET, "/v1/auth/me", session(sessionId), null).status())
        .isEqualTo(401);
    assertThat(call(HttpMethod.POST, "/v1/auth/exchange", session(sessionId), null).status())
        .isEqualTo(401);
  }

  @Test
  void aMalformedBodyIsAValidationError() {
    Resp resp = call(HttpMethod.POST, "/v1/auth/login", Map.of(), "{not json");
    assertThat(resp.status()).isEqualTo(400);
    assertThat(resp.json().get("code").asText()).isEqualTo("VALIDATION");
    Resp badNumber =
        call(HttpMethod.GET, "/v1/tenants?page=abc", session(readySession("acme", "admin1")), null);
    assertThat(badNumber.status()).isEqualTo(400);
  }

  @Test
  void anUnknownPathIsNotFoundForAnAuthenticatedCaller() {
    String sessionId = readySession("acme", "admin1");
    Resp resp = call(HttpMethod.GET, "/v1/nope", session(sessionId), null);
    assertThat(resp.status()).isEqualTo(404);
    assertThat(resp.json().get("code").asText()).isEqualTo("NOT_FOUND");
  }

  @Test
  void unexpectedErrorsDoNotLeakDetails() {
    var response =
        new tech.toolkit.tenantcore.api.web.ApiExceptionHandler()
            .unexpected(new IllegalStateException("password=secret-value"));
    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getBody().message()).doesNotContain("secret-value");
  }

  @Test
  void databaseSettingsFallBackToHostPortAndName() {
    var env =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("tenant-core.db.host", "db.internal")
            .withProperty("tenant-core.db.port", "6543")
            .withProperty("tenant-core.db.name", "core")
            .withProperty("tenant-core.db.owner-password", "o")
            .withProperty("tenant-core.db.app-password", "a");
    var config = new ApiConfig().databaseConfig(env);
    assertThat(config.jdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:6543/core");
    assertThat(config.ownerUser()).isEqualTo("tenant_core");
    assertThat(config.appUser()).isEqualTo("tenant_core_app");
  }

  @Test
  void healthIsPublic() {
    assertThat(call(HttpMethod.GET, "/actuator/health", Map.of(), null).status()).isEqualTo(200);
  }
}
