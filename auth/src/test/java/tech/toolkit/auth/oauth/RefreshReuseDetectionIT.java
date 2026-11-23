package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.OAuthTestBase;
import tools.jackson.databind.JsonNode;

/** FR-011 and RFC 9700: a used refresh token must never work again, and reuse ends the chain. */
class RefreshReuseDetectionIT extends OAuthTestBase {

  private JsonNode body(MvcResult result) throws Exception {
    return JSON.readTree(result.getResponse().getContentAsString());
  }

  private int authorizations() {
    return ownerJdbc().queryForObject("select count(*) from oauth2_authorization", Integer.class);
  }

  @Test
  void aUsedRefreshTokenIsRefusedAndTheWholeChainEnds() throws Exception {
    JsonNode tokens = obtainTokens();
    String old = tokens.get("refresh_token").asString();
    MvcResult rotated = refresh(CLIENT1, SECRET1, old);
    assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
    String newest = body(rotated).get("refresh_token").asString();

    MvcResult reuse = refresh(CLIENT1, SECRET1, old);

    assertThat(reuse.getResponse().getStatus()).isEqualTo(400);
    assertThat(body(reuse).get("error").asString()).isEqualTo("invalid_grant");
    assertThat(authorizations()).isZero();
    // the token that replaced the old one died with the chain
    assertThat(refresh(CLIENT1, SECRET1, newest).getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void theReuseIsAudited() throws Exception {
    JsonNode tokens = obtainTokens();
    String old = tokens.get("refresh_token").asString();
    refresh(CLIENT1, SECRET1, old);

    refresh(CLIENT1, SECRET1, old);

    var events =
        ownerJdbc()
            .queryForList(
                "select subject_user_id from auth_audit_event where type = 'REFRESH_REUSE_DETECTED'");
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("subject_user_id")).isNotNull();
  }

  @Test
  void usedTokensAreRememberedOnlyAsHashes() throws Exception {
    JsonNode tokens = obtainTokens();
    String old = tokens.get("refresh_token").asString();
    refresh(CLIENT1, SECRET1, old);

    List<String> stored =
        ownerJdbc().queryForList("select token_hash from auth_used_refresh_token", String.class);

    assertThat(stored).hasSize(1);
    assertThat(stored.get(0)).matches("[0-9a-f]{64}").doesNotContain(old);
  }

  @Test
  void twoCallersUsingTheSameTokenAtOnceNeverBothSucceed() throws Exception {
    JsonNode tokens = obtainTokens();
    String old = tokens.get("refresh_token").asString();
    CountDownLatch go = new CountDownLatch(1);
    Callable<Integer> call =
        () -> {
          go.await();
          return refresh(CLIENT1, SECRET1, old).getResponse().getStatus();
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<Integer>> results = new ArrayList<>();
    results.add(pool.submit(call));
    results.add(pool.submit(call));
    go.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> result : results) {
      statuses.add(result.get());
    }
    pool.shutdown();

    assertThat(statuses).containsExactlyInAnyOrder(200, 400);
  }
}
