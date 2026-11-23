package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.client.ClientExt;
import tech.toolkit.auth.client.ClientExtRepository;
import tech.toolkit.auth.support.OAuthTestBase;
import tools.jackson.databind.JsonNode;

/**
 * FR-011, FR-013, FR-014a: refresh tokens rotate, follow the SSO session and the client lifetimes.
 */
class RefreshRotationIT extends OAuthTestBase {

  @Autowired ClientExtRepository clientExt;

  private JsonNode body(MvcResult result) throws Exception {
    return JSON.readTree(result.getResponse().getContentAsString());
  }

  private static String sid(JsonNode tokens) throws Exception {
    return SignedJWT.parse(tokens.get("access_token").asString())
        .getJWTClaimsSet()
        .getStringClaim("sid");
  }

  private void assertInvalidGrant(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(body(result).get("error").asString()).isEqualTo("invalid_grant");
  }

  @Test
  void aRefreshIssuesNewTokensForTheSameLogin() throws Exception {
    JsonNode first = obtainTokens();

    MvcResult result = refresh(CLIENT1, SECRET1, first.get("refresh_token").asString());

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    JsonNode second = body(result);
    assertThat(second.get("access_token").asString())
        .isNotEqualTo(first.get("access_token").asString());
    assertThat(second.get("refresh_token").asString())
        .isNotEqualTo(first.get("refresh_token").asString());
    assertThat(second.get("expires_in").asInt()).isBetween(595, 600);
    assertThat(sid(second)).isEqualTo(sid(first));
  }

  @Test
  void aRefreshIsAuditedAsARefreshAndNotAsANewIssue() throws Exception {
    JsonNode tokens = obtainTokens();

    refresh(CLIENT1, SECRET1, tokens.get("refresh_token").asString());

    assertThat(count("TOKEN_ISSUED")).isEqualTo(1); // the code exchange
    assertThat(count("TOKEN_REFRESHED")).isEqualTo(1);
  }

  private int count(String type) {
    return ownerJdbc()
        .queryForObject(
            "select count(*) from auth_audit_event where type = ?", Integer.class, type);
  }

  @Test
  void theNewestRefreshTokenKeepsWorking() throws Exception {
    JsonNode tokens = obtainTokens();

    for (int i = 0; i < 3; i++) {
      MvcResult result = refresh(CLIENT1, SECRET1, tokens.get("refresh_token").asString());
      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      tokens = body(result);
    }
  }

  @Test
  void whenTheSsoSessionIsGoneTheRefreshIsRefused() throws Exception {
    JsonNode tokens = obtainTokens();
    ownerJdbc().update("delete from spring_session");

    assertInvalidGrant(refresh(CLIENT1, SECRET1, tokens.get("refresh_token").asString()));
  }

  @Test
  void whenTheSsoSessionWasIdleTooLongTheRefreshIsRefused() throws Exception {
    JsonNode tokens = obtainTokens();
    ownerJdbc()
        .update(
            "update spring_session set last_access_time = ?",
            System.currentTimeMillis() - Duration.ofMinutes(31).toMillis());

    assertInvalidGrant(refresh(CLIENT1, SECRET1, tokens.get("refresh_token").asString()));
  }

  @Test
  void whenTheSsoSessionIsOlderThanTheAbsoluteLimitTheRefreshIsRefused() throws Exception {
    JsonNode tokens = obtainTokens();
    ownerJdbc()
        .update(
            "update spring_session set creation_time = ?",
            System.currentTimeMillis() - Duration.ofHours(8).toMillis() - 60_000);

    assertInvalidGrant(refresh(CLIENT1, SECRET1, tokens.get("refresh_token").asString()));
  }

  @Test
  void aRefreshCountsAsActivityOfTheSsoSession() throws Exception {
    JsonNode tokens = obtainTokens();
    long twentyMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(20).toMillis();
    ownerJdbc().update("update spring_session set last_access_time = ?", twentyMinutesAgo);

    MvcResult result = refresh(CLIENT1, SECRET1, tokens.get("refresh_token").asString());

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    Long lastAccess =
        ownerJdbc().queryForObject("select last_access_time from spring_session", Long.class);
    assertThat(lastAccess)
        .isGreaterThan(System.currentTimeMillis() - Duration.ofSeconds(10).toMillis());
  }

  @Test
  void aClientCanHaveItsOwnTokenLifetimes() throws Exception {
    clientExt.upsert(new ClientExt(CLIENT1, true, null, null, 120, 3600));
    JsonNode tokens = obtainTokens();

    JsonNode refreshed = body(refresh(CLIENT1, SECRET1, tokens.get("refresh_token").asString()));

    assertThat(tokens.get("expires_in").asInt()).isLessThanOrEqualTo(120);
    assertThat(refreshed.get("expires_in").asInt()).isLessThanOrEqualTo(120);
  }

  @Test
  void anInvalidClientLifetimeConfigurationFallsBackToTheDefaults() throws Exception {
    // access longer than refresh would break FR-013: the override is ignored
    clientExt.upsert(new ClientExt(CLIENT1, true, null, null, 7200, 3600));

    JsonNode tokens = obtainTokens();

    assertThat(tokens.get("expires_in").asInt()).isBetween(595, 600);
  }
}
