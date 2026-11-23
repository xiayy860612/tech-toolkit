package tech.toolkit.auth.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import tech.toolkit.auth.support.OAuthTestBase;

/** FR-020 and the forced change after an administrator set the password. */
class ChangePasswordIT extends OAuthTestBase {

  private static final String NEW_PASSWORD = "New-Passw0rd-Strong!";

  private Browser signedIn() throws Exception {
    Browser browser = new Browser();
    String afterLogin = signIn(browser, USERNAME, PASSWORD).getResponse().getRedirectedUrl();
    assertThat(afterLogin).doesNotContain("error");
    return browser;
  }

  private MvcResult change(Browser browser, String current, String next) throws Exception {
    return browser.postForm(
        "/account/password", Map.of("currentPassword", current, "newPassword", next));
  }

  private String storedHash() {
    return ownerJdbc()
        .queryForObject(
            "select password_hash from auth_user where username = ?", String.class, USERNAME);
  }

  private static String pathAndQuery(String absolute) {
    URI uri = URI.create(absolute);
    return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
  }

  private int authorizations() {
    return ownerJdbc().queryForObject("select count(*) from oauth2_authorization", Integer.class);
  }

  @Test
  void theFormNeedsALogin() throws Exception {
    MvcResult result = new Browser().get("/account/password");

    assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
  }

  @Test
  void theCurrentPasswordMustBeRight() throws Exception {
    Browser browser = signedIn();
    String before = storedHash();

    MvcResult result = change(browser, "not-my-password", NEW_PASSWORD);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentAsString()).contains("当前密码不正确");
    assertThat(storedHash()).isEqualTo(before);
  }

  @Test
  void theNewPasswordMustMeetThePolicy() throws Exception {
    Browser browser = signedIn();
    String before = storedHash();

    MvcResult result = change(browser, PASSWORD, "short");

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentAsString()).contains("至少 12 位");
    assertThat(storedHash()).isEqualTo(before);
  }

  @Test
  void aChangeTakesEffectAndIsAudited() throws Exception {
    Browser browser = signedIn();

    MvcResult result = change(browser, PASSWORD, NEW_PASSWORD);

    assertThat(result.getResponse().getStatus()).isEqualTo(302);
    assertThat(signIn(new Browser(), USERNAME, NEW_PASSWORD).getResponse().getRedirectedUrl())
        .doesNotContain("error");
    assertThat(signIn(new Browser(), USERNAME, PASSWORD).getResponse().getRedirectedUrl())
        .isEqualTo("/login?error");
    var events =
        ownerJdbc()
            .queryForList(
                "select actor, subject_user_id from auth_audit_event where type = 'PASSWORD_CHANGED'");
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("actor")).isNotNull();
  }

  @Test
  void otherLoginsAndAllAuthorizationsEndButThisSessionStays() throws Exception {
    Browser mine = new Browser();
    obtainCode(mine, CLIENT1, REDIRECT1, challengeOf(newVerifier()));
    Browser other = new Browser();
    obtainCode(other, CLIENT2, REDIRECT2, challengeOf(newVerifier()));
    assertThat(authorizations()).isEqualTo(2);

    change(mine, PASSWORD, NEW_PASSWORD);

    assertThat(authorizations()).isZero();
    String again = authorizeUri(CLIENT2, REDIRECT2, "s", challengeOf(newVerifier()));
    assertThat(authorize(other, again).getResponse().getRedirectedUrl()).endsWith("/login");
    assertThat(authorize(mine, again).getResponse().getRedirectedUrl()).startsWith(REDIRECT2);
  }

  @Test
  void aUserWhoMustChangeThePasswordIsSentToTheFormFirstAndThenOn() throws Exception {
    ownerJdbc()
        .update("update auth_user set must_change_password = true where username = ?", USERNAME);
    Browser browser = new Browser();
    String authorize = authorizeUri(CLIENT1, REDIRECT1, "st", challengeOf(newVerifier()));
    authorize(browser, authorize);

    String afterLogin = signIn(browser, USERNAME, PASSWORD).getResponse().getRedirectedUrl();

    assertThat(afterLogin).endsWith("/account/password");
    // going straight to the authorization does not skip the change
    assertThat(authorize(browser, authorize).getResponse().getRedirectedUrl())
        .endsWith("/account/password");

    MvcResult changed = change(browser, PASSWORD, NEW_PASSWORD);

    String next = changed.getResponse().getRedirectedUrl();
    assertThat(next).contains("/oauth2/authorize");
    MvcResult granted = authorize(browser, pathAndQuery(next));
    assertThat(granted.getResponse().getRedirectedUrl()).startsWith(REDIRECT1);
    assertThat(
            UriComponentsBuilder.fromUriString(granted.getResponse().getRedirectedUrl())
                .build()
                .getQueryParams()
                .containsKey("code"))
        .isTrue();
    assertThat(
            ownerJdbc()
                .queryForObject(
                    "select must_change_password from auth_user where username = ?",
                    Boolean.class,
                    USERNAME))
        .isFalse();
  }
}
