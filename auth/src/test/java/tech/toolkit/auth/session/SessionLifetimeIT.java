package tech.toolkit.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;
import tech.toolkit.auth.support.OAuthTestBase;

/** FR-003: the SSO session ends after 30 minutes idle or 8 hours in total. */
class SessionLifetimeIT extends OAuthTestBase {

  private Browser loggedIn() throws Exception {
    Browser browser = new Browser();
    obtainCode(browser, CLIENT1, REDIRECT1, challengeOf(newVerifier()));
    return browser;
  }

  private String secondClient() throws Exception {
    return authorizeUri(CLIENT2, REDIRECT2, "st", challengeOf(newVerifier()));
  }

  private boolean granted(Browser browser) throws Exception {
    String location = authorize(browser, secondClient()).getResponse().getRedirectedUrl();
    return location != null
        && UriComponentsBuilder.fromUriString(location)
            .build()
            .getQueryParams()
            .containsKey("code");
  }

  @Test
  void aLiveSessionStaysValid() throws Exception {
    Browser browser = loggedIn();
    // created seven hours ago, active a moment ago: inside both limits
    long created = System.currentTimeMillis() - Duration.ofHours(7).toMillis();
    ownerJdbc().update("update spring_session set creation_time = ?", created);

    assertThat(granted(browser)).isTrue();
  }

  @Test
  void aSessionIdleForTooLongEnds() throws Exception {
    Browser browser = loggedIn();
    // Spring Session expires a session from its last access time plus the idle limit (30 minutes)
    ownerJdbc()
        .update(
            "update spring_session set last_access_time = ?",
            System.currentTimeMillis() - Duration.ofMinutes(31).toMillis());

    MvcResultHolder result =
        new MvcResultHolder(authorize(browser, secondClient()).getResponse().getRedirectedUrl());

    assertThat(result.location).endsWith("/login");
  }

  @Test
  void aSessionOlderThanTheAbsoluteLimitEndsEvenWhenActive() throws Exception {
    Browser browser = loggedIn();
    long created = System.currentTimeMillis() - Duration.ofHours(8).toMillis() - 60_000;
    ownerJdbc().update("update spring_session set creation_time = ?", created);

    MvcResultHolder result =
        new MvcResultHolder(authorize(browser, secondClient()).getResponse().getRedirectedUrl());

    assertThat(result.location).endsWith("/login");
  }

  private record MvcResultHolder(String location) {}
}
