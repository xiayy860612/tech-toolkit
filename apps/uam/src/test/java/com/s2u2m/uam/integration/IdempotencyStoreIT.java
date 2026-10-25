package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.port.IdempotencyStore.Claim;
import com.s2u2m.uam.infrastructure.redis.RedisGuards;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.TestSecrets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** T102：幂等存储的 Redis 实现——键 {@code uam:idem:{sha256(范围|键)}}，{@code SET NX} 抢占，完成后保存响应快照，TTL 10 分钟。 */
class IdempotencyStoreIT extends IntegrationTestBase {

  private RedisGuards store() {
    return new RedisGuards(redis, new LoginThrottlePolicy(5, Duration.ofMinutes(15)));
  }

  private static String redisKey(String scope, String key) {
    return "uam:idem:" + TestSecrets.sha256Hex(scope + "|" + key);
  }

  @Test
  void theFirstClaimWinsAndAnInFlightDuplicateSeesTheFingerprint() {
    RedisGuards store = store();

    assertThat(store.claim("admin-center", "key-1", "fp-1")).isEqualTo(new Claim.Acquired());
    assertThat(store.claim("admin-center", "key-1", "fp-2")).isEqualTo(new Claim.InFlight("fp-1"));

    Long ttl = redis.getExpire(redisKey("admin-center", "key-1"), TimeUnit.SECONDS);
    assertThat(ttl).isBetween(25L, 30L);
  }

  @Test
  void completeStoresTheSnapshotForTenMinutesAndReplaysReturnIt() {
    RedisGuards store = store();
    store.claim("admin-center", "key-1", "fp-1");

    store.complete("admin-center", "key-1", "fp-1", "{\"id\":\"x\"}");

    assertThat(store.claim("admin-center", "key-1", "fp-1"))
        .isEqualTo(new Claim.Completed("fp-1", "{\"id\":\"x\"}"));
    assertThat(redis.getExpire(redisKey("admin-center", "key-1"), TimeUnit.SECONDS))
        .isBetween(590L, 600L);
  }

  @Test
  void releaseFreesTheKeyForANewClaim() {
    RedisGuards store = store();
    store.claim("admin-center", "key-1", "fp-1");

    store.release("admin-center", "key-1");

    assertThat(store.claim("admin-center", "key-1", "fp-2")).isEqualTo(new Claim.Acquired());
  }

  @Test
  void keysAreScopedByClient() {
    RedisGuards store = store();
    store.claim("client-a", "key-1", "fp-1");

    assertThat(store.claim("client-b", "key-1", "fp-1")).isEqualTo(new Claim.Acquired());
    assertThat(redis.keys("uam:idem:*")).hasSize(2);
  }

  @Test
  void aCorruptedValueIsDiscardedAndClaimedAgain() {
    RedisGuards store = store();
    redis.opsForValue().set(redisKey("admin-center", "key-1"), "garbage");

    assertThat(store.claim("admin-center", "key-1", "fp-1")).isEqualTo(new Claim.Acquired());
  }

  @Test
  void aResponseContainingSeparatorsRoundTrips() {
    RedisGuards store = store();
    store.claim("admin-center", "key-1", "fp-1");
    store.complete("admin-center", "key-1", "fp-1", "a|b|c");

    assertThat(store.claim("admin-center", "key-1", "fp-1"))
        .isEqualTo(new Claim.Completed("fp-1", "a|b|c"));
  }
}
