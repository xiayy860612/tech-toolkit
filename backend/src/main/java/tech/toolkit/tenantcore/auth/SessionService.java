package tech.toolkit.tenantcore.auth;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.tenantcore.platform.AdminCenterProperties;

/**
 * 登录会话（由后端统一维护）：创建、解析并续期、撤销。令牌 256 位随机，只存 SHA-256； 空闲超时为滑动窗口（{@code last_seen_at} 写入有节流），绝对超时自登录起算。
 */
@Service
public class SessionService {

  public record Created(Session session, String token) {
    @Override
    public String toString() {
      return "Created[redacted]";
    }
  }

  private final SessionRepository sessions;
  private final AdminCenterProperties.Session config;
  private final Clock clock;
  private final JdbcTemplate jdbc;
  private final SecureRandom random = new SecureRandom();

  public SessionService(
      SessionRepository sessions,
      AdminCenterProperties properties,
      Clock clock,
      JdbcTemplate jdbc) {
    this.sessions = sessions;
    this.config = properties.session();
    this.clock = clock;
    this.jdbc = jdbc;
  }

  @Transactional
  public Created create(Long userId, LoginType loginType, InetAddress ip, String userAgent) {
    byte[] raw = new byte[32];
    random.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant now = clock.instant();
    String ua =
        userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 512));
    Session session =
        new Session(
            sha256(token),
            userId,
            loginType,
            now,
            now.plus(config.absoluteTimeout()),
            ip,
            ua,
            DeviceParser.parse(ua));
    return new Created(sessions.save(session), token);
  }

  /** 令牌 → 有效会话（并续期）；无效、过期或已撤销返回空。 */
  @Transactional
  public Optional<Session> resolve(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return sessions.findByTokenHash(sha256(token)).filter(this::isValid).map(this::touchIfDue);
  }

  /** 内部 JWT 的 {@code sid} → 有效会话（并续期）。 */
  @Transactional
  public Optional<Session> validate(UUID sessionId) {
    return sessions.findById(sessionId).filter(this::isValid).map(this::touchIfDue);
  }

  public boolean isValid(Session s) {
    Instant now = clock.instant();
    return s.getRevokedAt() == null
        && now.isBefore(s.getAbsoluteExpiresAt())
        && now.isBefore(s.getLastSeenAt().plus(config.idleTimeout()));
  }

  private Session touchIfDue(Session s) {
    Instant now = clock.instant();
    if (!now.isBefore(s.getLastSeenAt().plus(config.touchInterval()))) {
      sessions.touch(s.getId(), now);
      sessions.flush();
    }
    return s;
  }

  @Transactional
  public boolean revoke(UUID sessionId, RevokeReason reason) {
    return sessions.revokeById(sessionId, clock.instant(), reason) > 0;
  }

  @Transactional
  public int revokeOthersOfUser(Long userId, UUID keepSessionId, RevokeReason reason) {
    return sessions.revokeByUserExcept(userId, keepSessionId, clock.instant(), reason);
  }

  @Transactional
  public int revokeAllOfUser(Long userId, RevokeReason reason) {
    return sessions.revokeByUser(userId, clock.instant(), reason);
  }

  /** 跨租户清理过期/已撤销超过保留期的会话（数据库函数以属主身份执行，绕过 RLS）。 */
  public int purge() {
    Integer n =
        jdbc.queryForObject(
            "select purge_sessions(?::interval, ?::interval)",
            Integer.class,
            config.retention().toSeconds() + " seconds",
            config.idleTimeout().toSeconds() + " seconds");
    return n == null ? 0 : n;
  }

  static byte[] sha256(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
