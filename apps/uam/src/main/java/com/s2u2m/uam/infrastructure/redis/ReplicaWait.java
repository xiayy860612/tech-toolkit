package com.s2u2m.uam.infrastructure.redis;

import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 撤销类写入（退出、强制下线、停用）之后的 {@code WAIT 1 <短超时>}：等待至少一个副本确认，缩小主从切换时丢失撤销的窗口（research R7）。 单主且没有副本时立即返回 0。
 */
public class ReplicaWait {

  /** 在 WAIT 自身的超时之外，给客户端等待响应的宽限。 */
  private static final Duration GRACE = Duration.ofSeconds(1);

  private final StringRedisTemplate redis;

  public ReplicaWait(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** 等待至少 1 个副本确认，最多 {@code timeout}；返回已确认的副本数（可能为 0）。 */
  public long await(Duration timeout) {
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("WAIT 超时必须为正，0 表示无限等待");
    }
    long millis = Math.max(1, timeout.toMillis());
    Long acknowledged =
        redis.execute(
            (RedisCallback<Long>) connection -> waitFor(connection.getNativeConnection(), millis));
    return acknowledged == null ? 0L : acknowledged;
  }

  private static Long waitFor(Object nativeConnection, long millis) {
    try {
      return ((BaseRedisAsyncCommands<?, ?>) nativeConnection)
          .waitForReplication(1, millis)
          .get(millis + GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待 Redis 副本确认时被中断", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("等待 Redis 副本确认失败", e);
    }
  }
}
