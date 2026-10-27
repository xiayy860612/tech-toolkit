package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.port.IdempotencyStore.Claim;
import com.s2u2m.uam.infrastructure.redis.RedisGuards;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** T102：幂等存储的 Redis 实现——键 {@code uam:idem:{sha256(键)}}，{@code SET NX} 抢占，完成后保存响应快照，TTL 10 分钟。 */
class IdempotencyStoreIT extends IntegrationTestBase {

  private RedisGuards store() {
    return new RedisGuards(redis, new LoginThrottlePolicy(5, Duration.ofMinutes(15)));
  }

  private static String redisKey(String key) {
    return "uam:idem:" + sha256Hex(key);
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void theFirstClaimWinsAndAnInFlightDuplicateSeesTheFingerprint() {
    RedisGuards store = store();

    assertThat(store.claim("key-1", "fp-1")).isEqualTo(new Claim.Acquired());
    assertThat(store.claim("key-1", "fp-2")).isEqualTo(new Claim.InFlight("fp-1"));

    Long ttl = redis.getExpire(redisKey("key-1"), TimeUnit.SECONDS);
    assertThat(ttl).isBetween(25L, 30L);
  }

  @Test
  void completeStoresTheSnapshotForTenMinutesAndReplaysReturnIt() {
    RedisGuards store = store();
    store.claim("key-1", "fp-1");

    store.complete("key-1", "fp-1", "{\"id\":\"x\"}");

    assertThat(store.claim("key-1", "fp-1"))
        .isEqualTo(new Claim.Completed("fp-1", "{\"id\":\"x\"}"));
    assertThat(redis.getExpire(redisKey("key-1"), TimeUnit.SECONDS)).isBetween(590L, 600L);
  }

  @Test
  void releaseFreesTheKeyForANewClaim() {
    RedisGuards store = store();
    store.claim("key-1", "fp-1");

    store.release("key-1");

    assertThat(store.claim("key-1", "fp-2")).isEqualTo(new Claim.Acquired());
  }

  @Test
  void theRedisKeyIsTheHashOfTheBareKey() {
    RedisGuards store = store();

    store.claim("key-1", "fp-1");

    assertThat(redis.keys("uam:idem:*")).containsExactly(redisKey("key-1"));
  }

  @Test
  void aCorruptedValueIsDiscardedAndClaimedAgain() {
    RedisGuards store = store();
    redis.opsForValue().set(redisKey("key-1"), "garbage");

    assertThat(store.claim("key-1", "fp-1")).isEqualTo(new Claim.Acquired());
  }

  @Test
  void aResponseContainingSeparatorsRoundTrips() {
    RedisGuards store = store();
    store.claim("key-1", "fp-1");
    store.complete("key-1", "fp-1", "a|b|c");

    assertThat(store.claim("key-1", "fp-1")).isEqualTo(new Claim.Completed("fp-1", "a|b|c"));
  }
}
