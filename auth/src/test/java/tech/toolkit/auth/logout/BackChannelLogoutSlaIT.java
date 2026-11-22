package tech.toolkit.auth.logout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.client.ClientExt;
import tech.toolkit.auth.client.ClientExtRepository;
import tech.toolkit.auth.support.OAuthTestBase;
import tech.toolkit.auth.support.StubBackChannelReceiver;
import tools.jackson.databind.JsonNode;

/** SC-009: with the real scheduler running, the notice arrives within 10 seconds of logout. */
@TestPropertySource(properties = "auth.logout.worker-enabled=true")
class BackChannelLogoutSlaIT extends OAuthTestBase {

  @Autowired ClientExtRepository clientExt;

  @Test
  void theNoticeArrivesWithinTenSecondsWithoutAnyoneCallingTheWorker() throws Exception {
    try (StubBackChannelReceiver stub = new StubBackChannelReceiver()) {
      clientExt.upsert(new ClientExt(CLIENT1, true, stub.url(), null, null, null));
      Browser browser = new Browser();
      String verifier = newVerifier();
      CodeResult granted = obtainCode(browser, CLIENT1, REDIRECT1, challengeOf(verifier));
      JsonNode tokens =
          JSON.readTree(
              exchange(CLIENT1, SECRET1, granted.code(), REDIRECT1, verifier)
                  .getResponse()
                  .getContentAsString());

      MvcResult logout =
          browser.get("/connect/logout?id_token_hint=" + tokens.get("id_token").asString());
      assertThat(logout.getResponse().getStatus()).isEqualTo(302);

      await().atMost(Duration.ofSeconds(10)).until(() -> stub.logoutTokens().size() == 1);
    }
  }
}
