package com.s2u2m.uam.domain.policy;

import java.time.Duration;
import java.time.Instant;

/**
 * 会话时限（research R8）：空闲 30 分钟、绝对 12 小时（可配置）；有效到期与 Redis TTL 都取两者剩余的较小值；距上次写入超过 60
 * 秒才续期，避免每个页面请求都产生一次写。
 */
public final class SessionPolicy {

  private final Duration idle;
  private final Duration absolute;
  private final Duration touchInterval;

  public SessionPolicy(Duration idle, Duration absolute, Duration touchInterval) {
    this.idle = idle;
    this.absolute = absolute;
    this.touchInterval = touchInterval;
  }

  public Duration idle() {
    return idle;
  }

  public Duration absolute() {
    return absolute;
  }

  public Duration touchInterval() {
    return touchInterval;
  }

  public Instant absoluteExpiry(Instant loginAt) {
    return loginAt.plus(absolute);
  }

  /** 有效到期：空闲到期（最近活动 + 空闲时限）与绝对到期的较小者。 */
  public Instant expiry(Instant lastActiveAt, Instant absoluteExpiresAt) {
    Instant idleDeadline = lastActiveAt.plus(idle);
    return idleDeadline.isBefore(absoluteExpiresAt) ? idleDeadline : absoluteExpiresAt;
  }

  /** 距有效到期的剩余时间（不为负），即 Redis TTL。 */
  public Duration ttl(Instant now, Instant lastActiveAt, Instant absoluteExpiresAt) {
    Duration remaining = Duration.between(now, expiry(lastActiveAt, absoluteExpiresAt));
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  /** 距上次写入超过续期间隔才需要续期。 */
  public boolean shouldTouch(Instant lastActiveAt, Instant now) {
    return Duration.between(lastActiveAt, now).compareTo(touchInterval) > 0;
  }
}
