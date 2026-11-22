package tech.toolkit.auth.logout;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.client.ClientExt;
import tech.toolkit.auth.client.ClientExtRepository;
import tech.toolkit.auth.oauth.SigningKeyProvider;
import tech.toolkit.auth.support.OAuthTestBase;
import tech.toolkit.auth.support.StubBackChannelReceiver;
import tools.jackson.databind.JsonNode;

/** FR-014, FR-014a, SC-009: logout notices to every client of the session, with retries. */
@TestPropertySource(properties = "auth.logout.http-timeout=1s")
class BackChannelLogoutIT extends OAuthTestBase {

  private static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";
  private static final long[] RETRY_DELAYS = {1, 5, 30, 120, 600};

  @Autowired ClientExtRepository clientExt;
  @Autowired BackChannelDeliveryWorker worker;
  @Autowired SigningKeyProvider keys;

  StubBackChannelReceiver stub1;
  StubBackChannelReceiver stub2;

  @BeforeEach
  void startReceivers() throws Exception {
    stub1 = new StubBackChannelReceiver();
    stub2 = new StubBackChannelReceiver();
  }

  @AfterEach
  void stopReceivers() {
    stub1.close();
    stub2.close();
  }

  private void register(String clientId, StubBackChannelReceiver stub) {
    clientExt.upsert(new ClientExt(clientId, true, stub.url(), null, null, null));
  }

  /**
   * Logs in through client1, joins client2 by SSO, then logs out through client1. Returns the sid.
   */
  private String loginTwoClientsThenLogout() throws Exception {
    Browser browser = new Browser();
    String verifier1 = newVerifier();
    CodeResult first = obtainCode(browser, CLIENT1, REDIRECT1, challengeOf(verifier1));
    JsonNode tokens1 =
        JSON.readTree(
            exchange(CLIENT1, SECRET1, first.code(), REDIRECT1, verifier1)
                .getResponse()
                .getContentAsString());

    String verifier2 = newVerifier();
    MvcResult second =
        authorize(browser, authorizeUri(CLIENT2, REDIRECT2, "s2", challengeOf(verifier2)));
    String code2 =
        org.springframework.web.util.UriComponentsBuilder.fromUriString(
                second.getResponse().getRedirectedUrl())
            .build()
            .getQueryParams()
            .getFirst("code");
    exchange(CLIENT2, SECRET2, code2, REDIRECT2, verifier2);

    String sid =
        SignedJWT.parse(tokens1.get("access_token").asString())
            .getJWTClaimsSet()
            .getStringClaim("sid");
    MvcResult logout =
        browser.get(
            "/connect/logout?id_token_hint="
                + tokens1.get("id_token").asString()
                + "&post_logout_redirect_uri=http://127.0.0.1:8081/logged-out");
    assertThat(logout.getResponse().getStatus()).isEqualTo(302);
    return sid;
  }

  private Map<String, Object> deliveryOf(String clientId) {
    return ownerJdbc()
        .queryForMap(
            "select attempts, status, extract(epoch from (next_attempt_at - now())) as wait"
                + " from auth_logout_delivery where client_id = ?",
            clientId);
  }

  @Test
  void everyRegisteredClientOfTheSessionGetsASignedLogoutToken() throws Exception {
    register(CLIENT1, stub1);
    register(CLIENT2, stub2);
    String sid = loginTwoClientsThenLogout();

    worker.deliverDue();

    assertThat(stub1.logoutTokens()).hasSize(1);
    assertThat(stub2.logoutTokens()).hasSize(1);
    SignedJWT token = SignedJWT.parse(stub1.logoutTokens().get(0));
    assertThat(token.verify(new ECDSAVerifier(keys.current().toECPublicKey()))).isTrue();
    assertThat(token.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
    assertThat(token.getHeader().getType().getType()).isEqualTo("logout+jwt");
    JWTClaimsSet claims = token.getJWTClaimsSet();
    assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    assertThat(claims.getAudience()).containsExactly(CLIENT1);
    assertThat(claims.getIssueTime()).isNotNull();
    assertThat(claims.getJWTID()).isNotBlank();
    assertThat(claims.getStringClaim("sid")).isEqualTo(sid);
    assertThat(claims.getJSONObjectClaim("events")).containsKey(EVENT);
    assertThat(claims.getClaim("nonce")).isNull();
    String other = SignedJWT.parse(stub2.logoutTokens().get(0)).getJWTClaimsSet().getJWTID();
    assertThat(other).isNotEqualTo(claims.getJWTID());
    assertThat(deliveryOf(CLIENT1)).containsEntry("status", "DELIVERED");
  }

  @Test
  void clientsWithoutALogoutAddressAreNotNotified() throws Exception {
    register(CLIENT1, stub1);
    loginTwoClientsThenLogout();

    worker.deliverDue();

    assertThat(stub1.logoutTokens()).hasSize(1);
    assertThat(stub2.logoutTokens()).isEmpty();
    assertThat(
            ownerJdbc().queryForObject("select count(*) from auth_logout_delivery", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void aDeliveredNoticeIsNotSentAgain() throws Exception {
    register(CLIENT1, stub1);
    loginTwoClientsThenLogout();

    worker.deliverDue();
    worker.deliverDue();

    assertThat(stub1.logoutTokens()).hasSize(1);
  }

  @Test
  void failedDeliveriesBackOffAndThenGiveUp() throws Exception {
    stub1.respondWith(500);
    register(CLIENT1, stub1);
    loginTwoClientsThenLogout();

    for (int attempt = 1; attempt <= 6; attempt++) {
      worker.deliverDue();
      Map<String, Object> row = deliveryOf(CLIENT1);
      assertThat(row.get("attempts")).isEqualTo(attempt);
      if (attempt <= RETRY_DELAYS.length) {
        assertThat(row).containsEntry("status", "PENDING");
        double wait = ((Number) row.get("wait")).doubleValue();
        long expected = RETRY_DELAYS[attempt - 1];
        assertThat(wait).isBetween(expected - 2.0, expected + 1.0);
        ownerJdbc().update("update auth_logout_delivery set next_attempt_at = now()");
      }
    }

    assertThat(deliveryOf(CLIENT1)).containsEntry("status", "GAVE_UP");
    assertThat(stub1.logoutTokens()).hasSize(6);
    List<Map<String, Object>> audited =
        ownerJdbc()
            .queryForList(
                "select client_id, result from auth_audit_event where type = 'LOGOUT' and result = 'FAILURE'");
    assertThat(audited).extracting(e -> e.get("client_id")).containsExactly(CLIENT1);
  }

  @Test
  void aReceiverThatAnswersTooSlowlyCountsAsAFailure() throws Exception {
    stub1.delayResponsesBy(3_000);
    register(CLIENT1, stub1);
    loginTwoClientsThenLogout();

    worker.deliverDue();

    Map<String, Object> row = deliveryOf(CLIENT1);
    assertThat(row).containsEntry("status", "PENDING");
    assertThat(row.get("attempts")).isEqualTo(1);
  }

  @Test
  void twoInstancesNeverDeliverTheSameNoticeTwice() throws Exception {
    stub1.delayResponsesBy(300);
    register(CLIENT1, stub1);
    loginTwoClientsThenLogout();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Callable<Integer>> work = List.of(worker::deliverDue, worker::deliverDue);
    for (Future<Integer> done : pool.invokeAll(work)) {
      done.get();
    }
    pool.shutdown();

    assertThat(stub1.logoutTokens()).hasSize(1);
    assertThat(deliveryOf(CLIENT1)).containsEntry("status", "DELIVERED");
  }
}
