package tech.toolkit.auth.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TokenBucketLimiterTest {

  /** A clock the test moves by hand. */
  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-11-22T12:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final ManualClock clock = new ManualClock();
  private final TokenBucketLimiter limiter =
      new TokenBucketLimiter(3, Duration.ofSeconds(10), clock);

  @Test
  void allowsABurstUpToTheCapacityAndThenRefuses() {
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
    }
    assertThat(limiter.tryAcquire("10.0.0.1")).isFalse();
  }

  @Test
  void refillsOneTokenPerInterval() {
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("10.0.0.1");
    }

    clock.advance(Duration.ofSeconds(10));

    assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
    assertThat(limiter.tryAcquire("10.0.0.1")).isFalse();
  }

  @Test
  void neverRefillsBeyondTheCapacity() {
    limiter.tryAcquire("10.0.0.1");

    clock.advance(Duration.ofHours(1));

    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
    }
    assertThat(limiter.tryAcquire("10.0.0.1")).isFalse();
  }

  @Test
  void sourcesDoNotShareABucket() {
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("10.0.0.1");
    }

    assertThat(limiter.tryAcquire("10.0.0.2")).isTrue();
  }

  @Test
  void reportsHowLongUntilTheNextToken() {
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("10.0.0.1");
    }
    clock.advance(Duration.ofSeconds(4));

    assertThat(limiter.retryAfterSeconds("10.0.0.1")).isBetween(5L, 7L);
  }
}
