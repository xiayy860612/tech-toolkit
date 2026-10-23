package com.s2u2m.uam.infrastructure.redis;

import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.port.IdempotencyStore;
import com.s2u2m.uam.domain.port.LoginThrottle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的防护（限流、幂等）。登录失败限制：键 {@code uam:throttle:login:{sha256(规范化账号名|来源地址)}}，字符串计数；每次失败自增并把 TTL
 * 刷新为封锁时长， 达到阈值后该来源被拒绝，{@code Retry-After} 取剩余 TTL；成功即删除。只限来源，不锁账号。
 *
 * <p>幂等（新建用户）：键 {@code uam:idem:{sha256(客户端|幂等键)}}，值为字符串：进行中 {@code P|<指纹>}（TTL 30 秒）或完成 {@code
 * D|<指纹>|<响应快照>}（TTL 10 分钟）；{@code SET NX} 抢占，按客户端隔离，原始键不进 Redis 键名。
 */
@Component
@Profile("!cli")
public class RedisGuards implements LoginThrottle, IdempotencyStore {

  private static final String LOGIN_PREFIX = "uam:throttle:login:";
  private static final String IDEMPOTENCY_PREFIX = "uam:idem:";
  private static final String IN_FLIGHT = "P|";
  private static final String DONE = "D|";

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

  @Override
  public Claim claim(String scope, String key, String fingerprint) {
    String redisKey = idempotencyKey(scope, key);
    // 值在两次读取之间过期或被释放时重试；损坏的值（不是本类写入的）丢弃后重新抢占
    while (true) {
      if (Boolean.TRUE.equals(
          redis.opsForValue().setIfAbsent(redisKey, IN_FLIGHT + fingerprint, IN_FLIGHT_TTL))) {
        return new Claim.Acquired();
      }
      String value = redis.opsForValue().get(redisKey);
      if (value == null) {
        continue;
      }
      if (value.startsWith(IN_FLIGHT)) {
        return new Claim.InFlight(value.substring(IN_FLIGHT.length()));
      }
      int separator = value.startsWith(DONE) ? value.indexOf('|', DONE.length()) : -1;
      if (separator > 0) {
        return new Claim.Completed(
            value.substring(DONE.length(), separator), value.substring(separator + 1));
      }
      redis.delete(redisKey);
    }
  }

  @Override
  public void complete(String scope, String key, String fingerprint, String response) {
    redis
        .opsForValue()
        .set(idempotencyKey(scope, key), DONE + fingerprint + "|" + response, REPLAY_WINDOW);
  }

  @Override
  public void release(String scope, String key) {
    redis.delete(idempotencyKey(scope, key));
  }

  private static String idempotencyKey(String scope, String key) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((scope + "|" + key).getBytes(StandardCharsets.UTF_8));
      return IDEMPOTENCY_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
