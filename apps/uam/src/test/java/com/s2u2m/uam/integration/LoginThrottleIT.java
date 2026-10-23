package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.infrastructure.redis.RedisGuards;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * T063：登录限流的 Redis 实现——键 {@code uam:throttle:login:{sha256(账号名|来源)}}，5 次后锁该来源，TTL 即 Retry-After。
 */
class LoginThrottleIT extends IntegrationTestBase {

  private final LoginThrottlePolicy policy = new LoginThrottlePolicy(5, Duration.ofMinutes(15));

  private RedisGuards guards() {
    return new RedisGuards(redis, policy);
  }

  private String subject(String name, String ip) {
    return policy.subject(LoginName.of(name), ip);
  }

  @Test
  void blocksOnlyAfterTheFifthFailureAndReportsTheRemainingTtl() {
    RedisGuards guards = guards();
    String subject = subject("alice", "203.0.113.7");

    for (int i = 0; i < 4; i++) {
      guards.recordFailure(subject);
      assertThat(guards.blockedFor(subject)).isEmpty();
    }
    guards.recordFailure(subject);

    assertThat(guards.blockedFor(subject)).isPresent();
    assertThat(guards.blockedFor(subject).getAsLong()).isBetween(890L, 900L);
    assertThat(redis.opsForValue().get("uam:throttle:login:" + subject)).isEqualTo("5");
    assertThat(redis.getExpire("uam:throttle:login:" + subject, TimeUnit.SECONDS))
        .isBetween(890L, 900L);
  }

  @Test
  void failuresOfAnotherSourceOrAccountDoNotBlock() {
    RedisGuards guards = guards();
    for (int i = 0; i < 5; i++) {
      guards.recordFailure(subject("alice", "203.0.113.7"));
    }

    assertThat(guards.blockedFor(subject("alice", "203.0.113.7"))).isPresent();
    assertThat(guards.blockedFor(subject("alice", "198.51.100.9"))).isEmpty();
    assertThat(guards.blockedFor(subject("bob", "203.0.113.7"))).isEmpty();
  }

  @Test
  void resetClearsTheCounter() {
    RedisGuards guards = guards();
    String subject = subject("alice", "203.0.113.7");
    for (int i = 0; i < 3; i++) {
      guards.recordFailure(subject);
    }

    guards.reset(subject);

    assertThat(redis.hasKey("uam:throttle:login:" + subject)).isFalse();
    assertThat(guards.blockedFor(subject)).isEmpty();
  }

  @Test
  void everyFailureAlwaysCarriesATtlSoACounterCanNeverBecomeAPermanentBlock() {
    RedisGuards guards = guards();
    String subject = subject("alice", "203.0.113.7");

    guards.recordFailure(subject);

    assertThat(redis.getExpire("uam:throttle:login:" + subject, TimeUnit.SECONDS)).isPositive();
  }

  @Test
  void blockExpiresWithTheTtl() throws InterruptedException {
    RedisGuards guards = new RedisGuards(redis, new LoginThrottlePolicy(2, Duration.ofSeconds(1)));
    String subject = subject("alice", "203.0.113.7");
    guards.recordFailure(subject);
    guards.recordFailure(subject);
    assertThat(guards.blockedFor(subject)).hasValue(1L);

    Thread.sleep(1_300);

    assertThat(guards.blockedFor(subject)).isEmpty();
  }
}
