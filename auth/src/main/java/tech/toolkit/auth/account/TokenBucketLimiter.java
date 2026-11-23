package tech.toolkit.auth.account;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A token bucket per key (here: per source address). A bucket holds up to {@code capacity} tokens
 * and gets one back every {@code refillInterval}; each attempt takes one. The state lives in this
 * process, so with several instances the limit is per instance (a known limit, see research.md).
 */
public class TokenBucketLimiter {

  /** Above this many buckets, full idle ones are dropped so the map cannot grow without bound. */
  private static final int PRUNE_ABOVE = 10_000;

  private static final class Bucket {
    private double tokens;
    private long updatedAt;

    Bucket(double tokens, long updatedAt) {
      this.tokens = tokens;
      this.updatedAt = updatedAt;
    }
  }

  private final int capacity;
  private final long refillMillis;
  private final Clock clock;
  private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  public TokenBucketLimiter(int capacity, Duration refillInterval, Clock clock) {
    this.capacity = capacity;
    this.refillMillis = Math.max(1, refillInterval.toMillis());
    this.clock = clock;
  }

  /** Takes a token for the key; false when its bucket is empty. */
  public boolean tryAcquire(String key) {
    if (buckets.size() > PRUNE_ABOVE) {
      prune();
    }
    long now = clock.millis();
    Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
    synchronized (bucket) {
      refill(bucket, now);
      if (bucket.tokens >= 1) {
        bucket.tokens -= 1;
        return true;
      }
      return false;
    }
  }

  /** Seconds until the key gets its next token (at least 1). */
  public long retryAfterSeconds(String key) {
    long now = clock.millis();
    Bucket bucket = buckets.get(key);
    if (bucket == null) {
      return 1;
    }
    synchronized (bucket) {
      refill(bucket, now);
      double missing = Math.max(0, 1 - bucket.tokens);
      return Math.max(1, (long) Math.ceil(missing * refillMillis / 1000.0));
    }
  }

  private void refill(Bucket bucket, long now) {
    long elapsed = Math.max(0, now - bucket.updatedAt);
    bucket.tokens = Math.min(capacity, bucket.tokens + (double) elapsed / refillMillis);
    bucket.updatedAt = now;
  }

  private void prune() {
    long now = clock.millis();
    buckets
        .entrySet()
        .removeIf(
            entry -> {
              Bucket bucket = entry.getValue();
              synchronized (bucket) {
                refill(bucket, now);
                return bucket.tokens >= capacity;
              }
            });
  }
}
