package tech.toolkit.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.AdminApiTestBase;
import tools.jackson.databind.JsonNode;

/** FR-021, FR-022: one client's credentials, codes and tokens are of no use to another. */
class ClientIsolationIT extends AdminApiTestBase {

  private static final String CLIENT4 = "client4";
  private static final String SECRET4_REDIRECT = "http://127.0.0.1:8084/callback";

  private String registerClient4() throws Exception {
    MvcResult created =
        call(
            post("/admin/v1/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"clientId":"client4","type":"CONFIDENTIAL","redirectUris":["%s"],
                     "scopes":["openid"]}"""
                        .formatted(SECRET4_REDIRECT)));
    return json(created).get("clientSecret").asString();
  }

  @Test
  void aClientRegisteredOnlineCompletesTheLogin() throws Exception {
    String secret = registerClient4();
    String verifier = newVerifier();
    CodeResult granted =
        obtainCode(new Browser(), CLIENT4, SECRET4_REDIRECT, challengeOf(verifier));

    MvcResult response = exchange(CLIENT4, secret, granted.code(), SECRET4_REDIRECT, verifier);

    assertThat(response.getResponse().getStatus()).isEqualTo(200);
    JsonNode tokens = json(response);
    assertThat(tokens.get("access_token").asString()).isNotBlank();
    assertThat(tokens.get("id_token").asString()).isNotBlank();
  }

  @Test
  void theCredentialsOfOneClientDoNotOpenAnother() throws Exception {
    registerClient4();

    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));

    assertThat(
            exchange(CLIENT2, SECRET2, granted.code(), REDIRECT1, verifier)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void aCodeIssuedToOneClientCannotBeRedeemedByAnother() throws Exception {
    String secret4 = registerClient4();
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));

    int status =
        exchange(CLIENT4, secret4, granted.code(), SECRET4_REDIRECT, verifier)
            .getResponse()
            .getStatus();

    assertThat(status).isEqualTo(400);
  }

  @Test
  void aClientCannotSendTheUserToAnotherClientsRedirectUri() throws Exception {
    registerClient4();
    assertThatThrownBy(
            () -> obtainCode(new Browser(), CLIENT4, REDIRECT1, challengeOf(newVerifier())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aWrongSecretIsRefused() throws Exception {
    registerClient4();
    String verifier = newVerifier();
    CodeResult granted =
        obtainCode(new Browser(), CLIENT4, SECRET4_REDIRECT, challengeOf(verifier));

    assertThat(
            exchange(CLIENT4, "not-the-secret", granted.code(), SECRET4_REDIRECT, verifier)
                .getResponse()
                .getStatus())
        .isEqualTo(401);
  }

  @Test
  void aTokenIssuedToOneClientIsNotRevokedOrRefreshedByAnother() throws Exception {
    String secret4 = registerClient4();
    JsonNode tokens = obtainTokens();
    String refresh = tokens.get("refresh_token").asString();

    assertThat(refresh(CLIENT4, secret4, refresh).getResponse().getStatus()).isEqualTo(400);
    revoke(CLIENT4, secret4, refresh, "refresh_token");
    assertThat(refresh(CLIENT1, SECRET1, refresh).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void disablingClient2LeavesClient1AndClient3Working() throws Exception {
    registerClient4();
    JsonNode first = obtainTokens();

    call(post("/admin/v1/clients/" + CLIENT2 + "/disable"));

    String verifier = newVerifier();
    CodeResult again = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));
    assertThat(
            exchange(CLIENT1, SECRET1, again.code(), REDIRECT1, verifier).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(
            refresh(CLIENT1, SECRET1, first.get("refresh_token").asString())
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThatThrownBy(
            () -> obtainCode(new Browser(), CLIENT2, REDIRECT2, challengeOf(newVerifier())))
        .isInstanceOf(IllegalStateException.class);
  }
}
