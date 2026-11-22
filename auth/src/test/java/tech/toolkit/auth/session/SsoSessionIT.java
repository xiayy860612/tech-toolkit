package tech.toolkit.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import tech.toolkit.auth.support.OAuthTestBase;

class SsoSessionIT extends OAuthTestBase {

  private String uriFor2(String extra) throws Exception {
    return authorizeUri(CLIENT2, REDIRECT2, "st2", challengeOf(newVerifier())) + extra;
  }

  private static boolean grantsCode(MvcResult result, String redirect) {
    String location = result.getResponse().getRedirectedUrl();
    return location != null
        && location.startsWith(redirect + "?")
        && UriComponentsBuilder.fromUriString(location)
            .build()
            .getQueryParams()
            .containsKey("code");
  }

  @Test
  void aSecondClientAuthorizesWithoutAskingForCredentialsAgain() throws Exception {
    Browser browser = new Browser();
    obtainCode(browser, CLIENT1, REDIRECT1, challengeOf(newVerifier()));

    Instant start = Instant.now();
    MvcResult result = authorize(browser, uriFor2(""));
    Duration took = Duration.between(start, Instant.now());

    assertThat(grantsCode(result, REDIRECT2)).isTrue();
    assertThat(took).isLessThan(Duration.ofSeconds(3)); // SC-002
  }

  @Test
  void promptLoginForcesANewLoginEvenWithALiveSession() throws Exception {
    Browser browser = new Browser();
    obtainCode(browser, CLIENT1, REDIRECT1, challengeOf(newVerifier()));
    String uri = uriFor2("&prompt=login");

    MvcResult first = authorize(browser, uri);

    assertThat(first.getResponse().getRedirectedUrl()).endsWith("/login");
    // after logging in again the same request is granted, it does not loop back to the login page
    String back = signIn(browser, USERNAME, PASSWORD).getResponse().getRedirectedUrl();
    MvcResult second = authorize(browser, pathAndQuery(back));
    assertThat(grantsCode(second, REDIRECT2)).isTrue();
  }

  @Test
  void aLoginOlderThanMaxAgeForcesANewLogin() throws Exception {
    Browser browser = new Browser();
    obtainCode(browser, CLIENT1, REDIRECT1, challengeOf(newVerifier()));

    MvcResult result = authorize(browser, uriFor2("&max_age=0"));

    assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
  }

  @Test
  void aRecentLoginSatisfiesMaxAge() throws Exception {
    Browser browser = new Browser();
    obtainCode(browser, CLIENT1, REDIRECT1, challengeOf(newVerifier()));

    MvcResult result = authorize(browser, uriFor2("&max_age=3600"));

    assertThat(grantsCode(result, REDIRECT2)).isTrue();
  }

  @Test
  void bothClientsAreRecordedAgainstTheSameLogin() throws Exception {
    Browser browser = new Browser();
    obtainCode(browser, CLIENT1, REDIRECT1, challengeOf(newVerifier()));
    authorize(browser, uriFor2(""));

    var rows =
        ownerJdbc()
            .queryForList("select session_id, client_id from auth_sso_client order by client_id");

    assertThat(rows).extracting(r -> r.get("client_id")).containsExactly(CLIENT1, CLIENT2);
    assertThat(rows.get(0).get("session_id")).isEqualTo(rows.get(1).get("session_id"));
  }

  private static String pathAndQuery(String absolute) {
    var uri = java.net.URI.create(absolute);
    return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
  }
}
