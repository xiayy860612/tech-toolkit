package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.OAuthTestBase;
import tools.jackson.databind.JsonNode;

class AuthorizationCodeReuseIT extends OAuthTestBase {

  @Test
  void aCodeUsedTwiceIsRejectedAndEverythingItIssuedIsDeleted() throws Exception {
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));
    MvcResult first = exchange(CLIENT1, SECRET1, granted.code(), REDIRECT1, verifier);
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    String accessToken =
        JSON.readTree(first.getResponse().getContentAsString()).get("access_token").asString();

    MvcResult second = exchange(CLIENT1, SECRET1, granted.code(), REDIRECT1, verifier);

    assertThat(second.getResponse().getStatus()).isEqualTo(400);
    assertThat(second.getResponse().getContentAsString()).contains("invalid_grant");
    // the authorization and the tokens from the first exchange are gone
    assertThat(
            ownerJdbc().queryForObject("select count(*) from oauth2_authorization", Integer.class))
        .isZero();
    MvcResult userInfo =
        mvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andReturn();
    assertThat(userInfo.getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void aCodeUsedByAnotherClientIsRejected() throws Exception {
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));

    MvcResult response = exchange(CLIENT2, SECRET2, granted.code(), REDIRECT1, verifier);

    assertThat(response.getResponse().getStatus()).isEqualTo(400);
    JsonNode body = JSON.readTree(response.getResponse().getContentAsString());
    assertThat(body.get("error").asString()).isEqualTo("invalid_grant");
  }

  @Test
  void aCodeUsedWithAnotherRedirectUriIsRejected() throws Exception {
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));

    MvcResult response = exchange(CLIENT1, SECRET1, granted.code(), REDIRECT2, verifier);

    assertThat(response.getResponse().getStatus()).isEqualTo(400);
  }
}
