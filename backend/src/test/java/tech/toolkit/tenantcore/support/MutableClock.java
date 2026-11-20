package tech.toolkit.tenantcore.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 可前进的测试时钟，用于验证会话的空闲/绝对超时。 */
public class MutableClock extends Clock {

  private volatile Instant now = Instant.now();

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

  public void reset() {
    now = Instant.now();
  }

  public void advance(Duration d) {
    now = now.plus(d);
  }
}
