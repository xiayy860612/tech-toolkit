package com.s2u2m.uam.infrastructure.redis;

import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.port.LoginThrottle;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的防护（限流等）。登录失败限制：键 {@code uam:throttle:login:{sha256(规范化账号名|来源地址)}}，字符串计数；每次失败自增并把 TTL
 * 刷新为封锁时长， 达到阈值后该来源被拒绝，{@code Retry-After} 取剩余 TTL；成功即删除。只限来源，不锁账号。
 */
@Component
@Profile("!cli")
public class RedisGuards implements LoginThrottle {

  private static final String LOGIN_PREFIX = "uam:throttle:login:";

  private final StringRedisTemplate redis;
  private final LoginThrottlePolicy policy;
  private final DefaultRedisScript<Long> failScript = new DefaultRedisScript<>();

  public RedisGuards(StringRedisTemplate redis, LoginThrottlePolicy policy) {
    this.redis = redis;
    this.policy = policy;
    failScript.setLocation(new ClassPathResource("redis/throttle-fail.lua"));
    failScript.setResultType(Long.class);
  }

  @Override
  public OptionalLong blockedFor(String subject) {
    String key = LOGIN_PREFIX + subject;
    String value = redis.opsForValue().get(key);
    if (value == null || !policy.isBlocked(Long.parseLong(value))) {
      return OptionalLong.empty();
    }
    Long ttlMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
    if (ttlMillis == null || ttlMillis == -2) {
      return OptionalLong.empty();
    }
    if (ttlMillis < 0) {
      // 理论上不会出现（失败计数脚本总会设置 TTL）；兜底补上，避免永久封锁
      redis.expire(key, policy.block());
      return OptionalLong.of(policy.block().toSeconds());
    }
    return OptionalLong.of(Math.max(1, (ttlMillis + 999) / 1000));
  }

  @Override
  public void recordFailure(String subject) {
    redis.execute(
        failScript, List.of(LOGIN_PREFIX + subject), Long.toString(policy.block().toMillis()));
  }

  @Override
  public void reset(String subject) {
    redis.delete(LOGIN_PREFIX + subject);
  }
}
