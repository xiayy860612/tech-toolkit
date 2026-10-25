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
 * T063：登录限流的 Redis 实现——键 {@code uam:throttle:login:{sha256(账号名|来源)}}，前 5 次放行、之后锁该来源（闸门原子占用名额），TTL 即
 * Retry-After。
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
  void admitsTheFirstFiveAttemptsThenBlocksWithTheRemainingTtl() {
    RedisGuards guards = guards();
    String subject = subject("alice", "203.0.113.7");

    for (int i = 0; i < 5; i++) {
      assertThat(guards.admit(subject)).as("第 %d 次", i + 1).isEmpty();
    }
    assertThat(redis.opsForValue().get("uam:throttle:login:" + subject)).isEqualTo("5");
    assertThat(redis.getExpire("uam:throttle:login:" + subject, TimeUnit.SECONDS))
        .isBetween(890L, 900L);

    assertThat(guards.admit(subject)).isPresent();
    assertThat(guards.admit(subject).getAsLong()).isBetween(890L, 900L);
  }

  @Test
  void reachingTheMaximumResetsTheExpiryToTheBlockDuration() {
    RedisGuards guards = guards();
    String subject = subject("alice", "203.0.113.7");
    String key = "uam:throttle:login:" + subject;
    guards.admit(subject);
    redis.expire(key, Duration.ofSeconds(100));

    for (int i = 0; i < 4; i++) {
      guards.admit(subject);
    }

    assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(890L, 900L);
  }

  @Test
  void blockedAttemptsDoNotExtendTheBlock() throws InterruptedException {
    RedisGuards guards = new RedisGuards(redis, new LoginThrottlePolicy(1, Duration.ofSeconds(2)));
    String subject = subject("alice", "203.0.113.7");
    guards.admit(subject);
    Thread.sleep(1_100);

    assertThat(guards.admit(subject)).hasValue(1L);
    Thread.sleep(1_100);

    assertThat(guards.admit(subject)).isEmpty();
  }

  @Test
  void aCounterWithoutAnyTtlIsGivenOne() {
    RedisGuards guards = guards();
    String subject = subject("alice", "203.0.113.7");
    String key = "uam:throttle:login:" + subject;
    redis.opsForValue().set(key, "2");

    guards.admit(subject);

    assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isPositive();
  }

  @Test
  void attemptsOfAnotherSourceOrAccountDoNotBlock() {
    RedisGuards guards = guards();
    for (int i = 0; i < 6; i++) {
      guards.admit(subject("alice", "203.0.113.7"));
    }

    assertThat(guards.admit(subject("alice", "203.0.113.7"))).isPresent();
    assertThat(guards.admit(subject("alice", "198.51.100.9"))).isEmpty();
    assertThat(guards.admit(subject("bob", "203.0.113.7"))).isEmpty();
  }

  @Test
  void resetClearsTheCounter() {
    RedisGuards guards = guards();
    String subject = subject("alice", "203.0.113.7");
    for (int i = 0; i < 3; i++) {
      guards.admit(subject);
    }

    guards.reset(subject);

    assertThat(redis.hasKey("uam:throttle:login:" + subject)).isFalse();
    assertThat(guards.admit(subject)).isEmpty();
  }

  @Test
  void everyAttemptAlwaysCarriesATtlSoACounterCanNeverBecomeAPermanentBlock() {
    RedisGuards guards = guards();
    String subject = subject("alice", "203.0.113.7");

    guards.admit(subject);

    assertThat(redis.getExpire("uam:throttle:login:" + subject, TimeUnit.SECONDS)).isPositive();
  }

  @Test
  void blockExpiresWithTheTtl() throws InterruptedException {
    RedisGuards guards = new RedisGuards(redis, new LoginThrottlePolicy(2, Duration.ofSeconds(1)));
    String subject = subject("alice", "203.0.113.7");
    guards.admit(subject);
    guards.admit(subject);
    assertThat(guards.admit(subject)).hasValue(1L);

    Thread.sleep(1_300);

    assertThat(guards.admit(subject)).isEmpty();
  }
}
