package tech.toolkit.auth.logout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import tech.toolkit.auth.support.OAuthTestBase;
import tools.jackson.databind.JsonNode;

class LogoutIT extends OAuthTestBase {

  private static final String POST_LOGOUT = "http://127.0.0.1:8081/logged-out";

  private record Login(Browser browser, String idToken) {}

  private Login login() throws Exception {
    Browser browser = new Browser();
    String verifier = newVerifier();
    CodeResult granted = obtainCode(browser, CLIENT1, REDIRECT1, challengeOf(verifier));
    MvcResult tokens = exchange(CLIENT1, SECRET1, granted.code(), REDIRECT1, verifier);
    JsonNode body = JSON.readTree(tokens.getResponse().getContentAsString());
    return new Login(browser, body.get("id_token").asString());
  }

  private static String logoutUri(String idToken, String postLogout, String state) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/connect/logout").queryParam("id_token_hint", idToken);
    if (postLogout != null) {
      uri.queryParam("post_logout_redirect_uri", postLogout);
    }
    if (state != null) {
      uri.queryParam("state", state);
    }
    return uri.build().encode().toUriString();
  }

  private int authorizations() {
    return ownerJdbc().queryForObject("select count(*) from oauth2_authorization", Integer.class);
  }

  private boolean sessionIsAlive(Browser browser) throws Exception {
    String location =
        authorize(browser, authorizeUri(CLIENT2, REDIRECT2, "s", challengeOf(newVerifier())))
            .getResponse()
            .getRedirectedUrl();
    return location != null && location.startsWith(REDIRECT2);
  }

  @Test
  void logoutEndsTheSessionDeletesAuthorizationsAndGoesToTheRegisteredAddress() throws Exception {
    Login login = login();
    assertThat(authorizations()).isEqualTo(1);

    MvcResult result = login.browser().get(logoutUri(login.idToken(), POST_LOGOUT, "xyz"));

    assertThat(result.getResponse().getStatus())
        .as("error: %s", result.getResponse().getErrorMessage())
        .isEqualTo(302);
    assertThat(result.getResponse().getRedirectedUrl())
        .startsWith(POST_LOGOUT)
        .contains("state=xyz");
    assertThat(authorizations()).isZero();
    assertThat(sessionIsAlive(login.browser())).isFalse();
    List<Map<String, Object>> events =
        ownerJdbc()
            .queryForList(
                "select type, result from auth_audit_event where type in ('LOGOUT', 'SESSION_REVOKED')");
    assertThat(events).extracting(e -> e.get("type")).contains("LOGOUT", "SESSION_REVOKED");
  }

  @Test
  void withoutAPostLogoutAddressTheLoggedOutPageIsShown() throws Exception {
    Login login = login();

    MvcResult result = login.browser().get(logoutUri(login.idToken(), null, null));

    assertThat(result.getResponse().getRedirectedUrl()).endsWith("/logged-out");
    String page = login.browser().get("/logged-out").getResponse().getContentAsString();
    assertThat(page).contains("已退出");
  }

  @Test
  void anUnregisteredPostLogoutAddressIsRejectedAndNothingEnds() throws Exception {
    Login login = login();

    MvcResult result =
        login.browser().get(logoutUri(login.idToken(), "http://evil.example/bye", "s"));

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(result.getResponse().getRedirectedUrl()).isNull();
    assertThat(authorizations()).isEqualTo(1);
    assertThat(sessionIsAlive(login.browser())).isTrue();
  }

  @Test
  void aGarbageIdTokenHintIsRejected() throws Exception {
    Login login = login();

    MvcResult result = login.browser().get(logoutUri("not-a-token", POST_LOGOUT, null));

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(sessionIsAlive(login.browser())).isTrue();
  }

  @Test
  void anIdTokenOfAnotherLoginCannotEndThisSession() throws Exception {
    Login mine = login();
    Login other = login();

    MvcResult result = mine.browser().get(logoutUri(other.idToken(), POST_LOGOUT, null));

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(authorizations()).isEqualTo(2);
    assertThat(sessionIsAlive(mine.browser())).isTrue();
  }
}
