package com.s2u2m.uam.infrastructure.redis;

import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.port.IdempotencyStore;
import com.s2u2m.uam.domain.port.LoginThrottle;
import com.s2u2m.uam.domain.port.PasswordChangeThrottle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的防护（限流、幂等）。登录失败限制：键 {@code uam:throttle:login:{sha256(规范化账号名|来源地址)}}，字符串计数；闸门在哈希比对之前由一个
 * Lua 脚本原子完成「自增 → 首次设窗口 TTL → 计数达到上限时把 TTL 重置为封锁时长 → 超过上限则拒绝」，并发的错误尝试不会同时通过； 前 {@code max}
 * 次放行，之后的被拒绝，{@code Retry-After} 取剩余 TTL；成功即删除。只限来源，不锁账号。
 *
 * <p>改密限流（当前密码连续错误）口径相同，键为 {@code uam:throttle:pwchg:{sha256(账号标识|来源地址)}}，与登录计数互不影响。
 *
 * <p>幂等（新建用户）：键 {@code uam:idem:{sha256(幂等键)}}，值为字符串：进行中 {@code P|<指纹>}（TTL 30 秒）或完成 {@code
 * D|<指纹>|<响应快照>}（TTL 10 分钟）；{@code SET NX} 抢占，原始键不进 Redis 键名。
 */
@Component
@Profile("!cli")
public class RedisGuards implements LoginThrottle, PasswordChangeThrottle, IdempotencyStore {

  private static final String LOGIN_PREFIX = "uam:throttle:login:";
  private static final String PASSWORD_CHANGE_PREFIX = "uam:throttle:pwchg:";
  private static final String IDEMPOTENCY_PREFIX = "uam:idem:";
  private static final String IN_FLIGHT = "P|";
  private static final String DONE = "D|";

  private final StringRedisTemplate redis;
  private final LoginThrottlePolicy policy;
  private final DefaultRedisScript<Long> admitScript = new DefaultRedisScript<>();

  public RedisGuards(StringRedisTemplate redis, LoginThrottlePolicy policy) {
    this.redis = redis;
    this.policy = policy;
    admitScript.setLocation(new ClassPathResource("redis/throttle-admit.lua"));
    admitScript.setResultType(Long.class);
  }

  @Override
  public OptionalLong admit(String subject) {
    return admit(LOGIN_PREFIX, subject);
  }

  @Override
  public OptionalLong admitChange(String subject) {
    return admit(PASSWORD_CHANGE_PREFIX, subject);
  }

  private OptionalLong admit(String prefix, String subject) {
    Long remainingMillis =
        redis.execute(
            admitScript,
            List.of(prefix + subject),
            Integer.toString(policy.maxFailures()),
            Long.toString(policy.block().toMillis()));
    if (remainingMillis == null || remainingMillis <= 0) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(Math.max(1, (remainingMillis + 999) / 1000));
  }

  @Override
  public void reset(String subject) {
    redis.delete(LOGIN_PREFIX + subject);
  }

  @Override
  public void resetChange(String subject) {
    redis.delete(PASSWORD_CHANGE_PREFIX + subject);
  }

  @Override
  public Claim claim(String key, String fingerprint) {
    String redisKey = idempotencyKey(key);
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
  public void complete(String key, String fingerprint, String response) {
    redis
        .opsForValue()
        .set(idempotencyKey(key), DONE + fingerprint + "|" + response, REPLAY_WINDOW);
  }

  @Override
  public void release(String key) {
    redis.delete(idempotencyKey(key));
  }

  private static String idempotencyKey(String key) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      return IDEMPOTENCY_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
