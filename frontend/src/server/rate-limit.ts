/** 限流所需的最小 Redis 接口（ioredis 与 ioredis-mock 均满足）。 */
export interface RedisLike {
  incr(key: string): Promise<number>;
  expire(key: string, seconds: number): Promise<number>;
  ttl(key: string): Promise<number>;
}

export interface RateLimitOptions {
  max: number;
  windowSeconds: number;
}

export interface RateLimitResult {
  allowed: boolean;
  retryAfterSeconds: number;
}

/** 按「路由组 + 客户端 IP」的固定窗口计数：Redis INCR + EXPIRE。 */
export function createRateLimiter(redis: RedisLike) {
  return {
    async check(group: string, ip: string, opts: RateLimitOptions): Promise<RateLimitResult> {
      const key = `rl:${group}:${ip}`;
      const count = await redis.incr(key);
      let ttl = await redis.ttl(key);
      if (count === 1 || ttl < 0) {
        // 首次计数，或 key 丢失了过期时间（崩溃/手工写入）：补设窗口，避免永久封禁
        await redis.expire(key, opts.windowSeconds);
        ttl = opts.windowSeconds;
      }
      return count > opts.max
        ? { allowed: false, retryAfterSeconds: Math.max(1, ttl) }
        : { allowed: true, retryAfterSeconds: 0 };
    },
  };
}
