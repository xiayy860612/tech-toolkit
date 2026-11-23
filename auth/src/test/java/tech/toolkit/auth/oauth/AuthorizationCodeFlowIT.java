package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.OAuthTestBase;
import tools.jackson.databind.JsonNode;

class AuthorizationCodeFlowIT extends OAuthTestBase {

  @Test
  void anUnauthenticatedUserIsSentToTheLoginPage() throws Exception {
    String uri = authorizeUri(CLIENT1, REDIRECT1, "st", challengeOf(newVerifier()));

    MvcResult result = authorize(new Browser(), uri);

    assertThat(result.getResponse().getStatus()).isEqualTo(302);
    assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
  }

  @Test
  void completesTheFullAuthorizationCodeFlowWithPkce() throws Exception {
    String verifier = newVerifier();

    CodeResult granted = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));

    // no consent page: the second authorize call goes straight to the registered redirect URI
    assertThat(granted.redirectLocation()).startsWith(REDIRECT1 + "?");
    assertThat(granted.redirectLocation()).contains("code=").contains("state=state-1");
    // nothing but the code travels through the browser
    assertThat(granted.redirectLocation())
        .doesNotContain("access_token")
        .doesNotContain("refresh_token")
        .doesNotContain("id_token");

    MvcResult response = exchange(CLIENT1, SECRET1, granted.code(), REDIRECT1, verifier);

    assertThat(response.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.getResponse().getContentAsString());
    assertThat(body.get("token_type").asString()).isEqualTo("Bearer");
    assertThat(body.get("access_token").asString()).isNotBlank();
    assertThat(body.get("id_token").asString()).isNotBlank();
    assertThat(body.get("expires_in").asInt()).isBetween(595, 600);
  }

  @Test
  void theAuthorizationCodeLivesForSixtySeconds() throws Exception {
    obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(newVerifier()));

    Double seconds =
        ownerJdbc()
            .queryForObject(
                "select extract(epoch from (authorization_code_expires_at"
                    + " - authorization_code_issued_at)) from oauth2_authorization",
                Double.class);

    assertThat(seconds).isEqualTo(60.0);
  }

  @Test
  void aWrongCodeVerifierIsRejected() throws Exception {
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));

    MvcResult response = exchange(CLIENT1, SECRET1, granted.code(), REDIRECT1, newVerifier());

    assertThat(response.getResponse().getStatus()).isEqualTo(400);
    assertThat(response.getResponse().getContentAsString()).contains("invalid_grant");
  }

  @Test
  void aWrongClientSecretIsRejected() throws Exception {
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));

    MvcResult response = exchange(CLIENT1, "not-the-secret", granted.code(), REDIRECT1, verifier);

    assertThat(response.getResponse().getStatus()).isEqualTo(401);
  }
}
