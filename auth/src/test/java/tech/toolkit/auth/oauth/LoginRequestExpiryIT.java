package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.OAuthTestBase;

/** FR-002a: a login request is only valid for a limited time after the login page was shown. */
@TestPropertySource(properties = "auth.login.request-ttl-seconds=2")
class LoginRequestExpiryIT extends OAuthTestBase {

  private MvcResult submit(Browser browser) throws Exception {
    return browser.postForm("/login", Map.of("username", USERNAME, "password", PASSWORD));
  }

  @Test
  void aTimelySubmissionSignsIn() throws Exception {
    Browser browser = new Browser();
    browser.get("/login");

    MvcResult result = submit(browser);

    assertThat(result.getResponse().getStatus()).isEqualTo(302);
    assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("error");
  }

  @Test
  void aLateSubmissionIsNotProcessedAndOffersToRestartTheAuthorization() throws Exception {
    Browser browser = new Browser();
    String authorize = authorizeUri(CLIENT1, REDIRECT1, "st", challengeOf(newVerifier()));
    authorize(browser, authorize);
    browser.get("/login");
    Thread.sleep(2_500);

    MvcResult result = submit(browser);

    // MockMvc records a forward instead of executing it, so the expired page is fetched directly
    assertThat(result.getResponse().getForwardedUrl()).isEqualTo("/login/expired");
    String page = browser.get("/login/expired").getResponse().getContentAsString();
    assertThat(page).contains("登录请求已过期").contains("/oauth2/authorize?");
    // the credentials were never checked: still not signed in
    assertThat(authorize(browser, authorize).getResponse().getRedirectedUrl()).endsWith("/login");
  }

  @Test
  void aSubmissionWithoutAnyPriorLoginPageOnlyAsksToGoBackToTheApplication() throws Exception {
    Browser browser = new Browser();

    MvcResult result = submit(browser);

    assertThat(result.getResponse().getForwardedUrl()).isEqualTo("/login/expired");
    String page = browser.get("/login/expired").getResponse().getContentAsString();
    assertThat(page).contains("登录请求已过期").contains("请回到应用重新登录");
    assertThat(page).doesNotContain("/oauth2/authorize");
  }
}
