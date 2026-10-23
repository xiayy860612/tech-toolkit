package com.s2u2m.uam.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.policy.SessionPolicy;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** T045：会话空闲 30 分钟、绝对 12 小时，Redis TTL 取两者剩余的较小值；距上次写入超过 60 秒才续期。 */
class SessionPolicyTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");

  private final SessionPolicy policy =
      new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12), Duration.ofSeconds(60));

  @Test
  void absoluteExpiryIsLoginPlusAbsoluteLifetime() {
    assertThat(policy.absoluteExpiry(T0)).isEqualTo(T0.plus(Duration.ofHours(12)));
  }

  @Test
  void expiryIsIdleDeadlineWhileFarFromTheAbsoluteLimit() {
    Instant absolute = policy.absoluteExpiry(T0);

    assertThat(policy.expiry(T0, absolute)).isEqualTo(T0.plus(Duration.ofMinutes(30)));
  }

  @Test
  void expiryIsCappedByTheAbsoluteLimit() {
    Instant absolute = policy.absoluteExpiry(T0);
    Instant lastActive = absolute.minus(Duration.ofMinutes(10));

    assertThat(policy.expiry(lastActive, absolute)).isEqualTo(absolute);
  }

  @Test
  void ttlIsTheSmallerOfIdleAndAbsoluteRemaining() {
    Instant absolute = T0.plus(Duration.ofHours(12));

    // 空闲剩余 30 分钟 < 绝对剩余 12 小时
    assertThat(policy.ttl(T0, T0, absolute)).isEqualTo(Duration.ofMinutes(30));
    // 活动 10 分钟前：空闲剩余 20 分钟
    assertThat(policy.ttl(T0.plus(Duration.ofMinutes(10)), T0, absolute))
        .isEqualTo(Duration.ofMinutes(20));
    // 临近绝对到期：绝对剩余 5 分钟 < 空闲剩余 30 分钟
    Instant late = absolute.minus(Duration.ofMinutes(5));
    assertThat(policy.ttl(late, late, absolute)).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void ttlNeverGoesNegative() {
    Instant absolute = T0.plus(Duration.ofHours(12));

    assertThat(policy.ttl(absolute.plusSeconds(1), T0, absolute)).isEqualTo(Duration.ZERO);
  }

  @Test
  void touchOnlyAfterMoreThanTheTouchInterval() {
    assertThat(policy.shouldTouch(T0, T0.plusSeconds(59))).isFalse();
    assertThat(policy.shouldTouch(T0, T0.plusSeconds(60))).isFalse();
    assertThat(policy.shouldTouch(T0, T0.plusSeconds(61))).isTrue();
  }

  @Test
  void exposesConfiguredDurations() {
    assertThat(policy.idle()).isEqualTo(Duration.ofMinutes(30));
    assertThat(policy.absolute()).isEqualTo(Duration.ofHours(12));
    assertThat(policy.touchInterval()).isEqualTo(Duration.ofSeconds(60));
  }
}
