package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import tech.toolkit.auth.support.OAuthTestBase;

class AuthorizationRequestValidationIT extends OAuthTestBase {

  private String good() throws Exception {
    return authorizeUri(CLIENT1, REDIRECT1, "st", challengeOf(newVerifier()));
  }

  private MvcResult call(String uri) throws Exception {
    return authorize(new Browser(), uri);
  }

  /** An invalid client or redirect URI must show an error page, never redirect to the URI. */
  private void assertErrorPageWithoutRedirect(MvcResult result) {
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(result.getResponse().getRedirectedUrl()).isNull();
  }

  /** Other errors go back to the registered redirect URI with an error parameter. */
  private String redirectError(MvcResult result) {
    assertThat(result.getResponse().getStatus()).isEqualTo(302);
    String location = result.getResponse().getRedirectedUrl();
    assertThat(location).startsWith(REDIRECT1);
    return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("error");
  }

  @Test
  void anUnregisteredClientShowsAnErrorPage() throws Exception {
    assertErrorPageWithoutRedirect(
        call(authorizeUri("nobody", REDIRECT1, "st", challengeOf(newVerifier()))));
  }

  @Test
  void aRedirectUriThatDiffersFromTheRegisteredOneShowsAnErrorPage() throws Exception {
    // trailing slash, different case, different port, other host
    for (String uri :
        new String[] {
          REDIRECT1 + "/",
          "http://127.0.0.1:8081/Callback",
          "http://127.0.0.1:9999/callback",
          "http://evil.example/callback"
        }) {
      assertErrorPageWithoutRedirect(
          call(authorizeUri(CLIENT1, uri, "st", challengeOf(newVerifier()))));
    }
  }

  @Test
  void aMissingCodeChallengeIsRejected() throws Exception {
    String uri =
        good()
            .replaceAll("&code_challenge=[^&]*", "")
            .replaceAll("&code_challenge_method=[^&]*", "");

    assertThat(redirectError(call(uri))).isEqualTo("invalid_request");
  }

  @Test
  void onlyS256IsAccepted() throws Exception {
    String uri = good().replace("code_challenge_method=S256", "code_challenge_method=plain");

    assertThat(redirectError(call(uri))).isEqualTo("invalid_request");
  }

  @Test
  void aMissingStateIsRejected() throws Exception {
    String uri = good().replaceAll("&state=[^&]*", "");

    assertThat(redirectError(call(uri))).isEqualTo("invalid_request");
  }

  @Test
  void onlyTheCodeResponseTypeIsAccepted() throws Exception {
    String uri = good().replace("response_type=code", "response_type=token");

    MvcResult result = call(uri);

    assertThat(result.getResponse().getStatus()).isIn(302, 400);
    if (result.getResponse().getRedirectedUrl() != null) {
      assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("access_token");
    }
  }

  @Test
  void aScopeOutsideTheRegisteredOnesIsRejected() throws Exception {
    String uri = good().replace("scope=openid", "scope=openid%20admin");

    assertThat(redirectError(call(uri))).isEqualTo("invalid_scope");
  }
}
