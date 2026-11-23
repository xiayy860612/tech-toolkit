package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.client.ClientExt;
import tech.toolkit.auth.client.ClientExtRepository;
import tech.toolkit.auth.support.OAuthTestBase;
import tools.jackson.databind.JsonNode;

/** FR-012: refresh tokens stop working when the user, the password state or the client changes. */
class RevocationIT extends OAuthTestBase {

  @Autowired ClientExtRepository clientExt;

  private void assertInvalidGrant(MvcResult result) throws Exception {
    MockHttpServletResponse response = result.getResponse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(JSON.readTree(response.getContentAsString()).get("error").asString())
        .isEqualTo("invalid_grant");
  }

  private static String refreshTokenOf(JsonNode tokens) {
    return tokens.get("refresh_token").asString();
  }

  @Test
  void aDisabledUserCannotRefresh() throws Exception {
    JsonNode tokens = obtainTokens();
    ownerJdbc().update("update auth_user set status = 'DISABLED' where username = ?", USERNAME);

    assertInvalidGrant(refresh(CLIENT1, SECRET1, refreshTokenOf(tokens)));
  }

  @Test
  void aPasswordChangedAfterTheTokensWereIssuedEndsTheRefresh() throws Exception {
    JsonNode tokens = obtainTokens();
    // the password change is written with the application's clock, like the token's issue time
    ownerJdbc()
        .update(
            "update auth_user set password_changed_at = ? where username = ?",
            java.sql.Timestamp.from(java.time.Instant.now()),
            USERNAME);

    assertInvalidGrant(refresh(CLIENT1, SECRET1, refreshTokenOf(tokens)));
  }

  @Test
  void aDeactivatedClientCannotRefreshAndOthersAreUnaffected() throws Exception {
    JsonNode first = obtainTokens();
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT2, REDIRECT2, challengeOf(verifier));
    JsonNode second =
        JSON.readTree(
            exchange(CLIENT2, SECRET2, granted.code(), REDIRECT2, verifier)
                .getResponse()
                .getContentAsString());
    clientExt.upsert(new ClientExt(CLIENT1, false, null, null, null, null));

    // a disabled client is unknown to the token endpoint: it fails client authentication (FR-022)
    MvcResult refused = refresh(CLIENT1, SECRET1, refreshTokenOf(first));
    assertThat(refused.getResponse().getStatus()).isEqualTo(401);
    assertThat(JSON.readTree(refused.getResponse().getContentAsString()).get("error").asString())
        .isEqualTo("invalid_client");
    assertThat(refresh(CLIENT2, SECRET2, refreshTokenOf(second)).getResponse().getStatus())
        .isEqualTo(200);
  }

  @Test
  void revokingTheRefreshTokenStopsTheRefreshAndIsAudited() throws Exception {
    JsonNode tokens = obtainTokens();

    MvcResult revoked = revoke(CLIENT1, SECRET1, refreshTokenOf(tokens), "refresh_token");

    assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
    assertInvalidGrant(refresh(CLIENT1, SECRET1, refreshTokenOf(tokens)));
    assertThat(
            ownerJdbc()
                .queryForObject(
                    "select count(*) from auth_audit_event where type = 'TOKEN_REVOKED'",
                    Integer.class))
        .isEqualTo(1);
  }

  @Test
  void revokingNeedsClientAuthentication() throws Exception {
    JsonNode tokens = obtainTokens();

    MvcResult result = revoke(null, null, refreshTokenOf(tokens), "refresh_token");

    assertThat(result.getResponse().getStatus()).isEqualTo(401);
    assertThat(refresh(CLIENT1, SECRET1, refreshTokenOf(tokens)).getResponse().getStatus())
        .isEqualTo(200);
  }

  @Test
  void aClientCannotRevokeTheTokenOfAnotherClient() throws Exception {
    JsonNode tokens = obtainTokens();

    revoke(CLIENT2, SECRET2, refreshTokenOf(tokens), "refresh_token");

    assertThat(refresh(CLIENT1, SECRET1, refreshTokenOf(tokens)).getResponse().getStatus())
        .isEqualTo(200);
  }
}
