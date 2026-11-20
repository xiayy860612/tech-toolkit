package tech.toolkit.tenantcore.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.tenantcore.audit.AuditAction;
import tech.toolkit.tenantcore.audit.AuditEvent;
import tech.toolkit.tenantcore.audit.AuditService;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.platform.Actor;
import tech.toolkit.tenantcore.platform.AdminCenterProperties;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.PageResult;

/** 管理员查看本租户有效会话、强制下线单个会话或某用户的全部会话；撤销与审计同事务。 */
@Service
public class SessionAdminService {

  /** 列表行：会话本身与展示所需的用户信息；{@code current} 依据 JWT 的 sid。 */
  public record Item(
      Session session, String displayName, String username, Instant expiresAt, boolean current) {}

  private final SessionQueryRepository queries;
  private final SessionService sessions;
  private final SessionRepository sessionRepository;
  private final AppUserRepository users;
  private final AuditService audit;
  private final AdminCenterProperties.Session config;
  private final Clock clock;

  public SessionAdminService(
      SessionQueryRepository queries,
      SessionService sessions,
      SessionRepository sessionRepository,
      AppUserRepository users,
      AuditService audit,
      AdminCenterProperties properties,
      Clock clock) {
    this.queries = queries;
    this.sessions = sessions;
    this.sessionRepository = sessionRepository;
    this.users = users;
    this.audit = audit;
    this.config = properties.session();
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResult<Item> list(String keyword, UUID currentSessionId, int page, int size) {
    Instant now = clock.instant();
    return queries
        .activePage(keyword, now, now.minus(config.idleTimeout()), page, size)
        .map(
            r -> {
              Session s = r.session();
              Instant expires = s.getLastSeenAt().plus(config.idleTimeout());
              if (s.getAbsoluteExpiresAt().isBefore(expires)) {
                expires = s.getAbsoluteExpiresAt();
              }
              return new Item(
                  s, r.displayName(), r.username(), expires, s.getId().equals(currentSessionId));
            });
  }

  /** 会话不存在、已失效或属于其他租户时静默成功（幂等，不泄露存在性）。 */
  @Transactional
  public void revoke(Actor actor, UUID currentSessionId, UUID sessionId) {
    if (sessionId.equals(currentSessionId)) {
      throw new ApiException(ErrorCode.CANNOT_REVOKE_CURRENT_SESSION);
    }
    Session target = sessionRepository.findById(sessionId).orElse(null);
    if (target == null || !sessions.isValid(target)) {
      return;
    }
    if (sessions.revoke(sessionId, RevokeReason.ADMIN_REVOKED)) {
      audit.record(
          AuditEvent.success(
              AuditAction.SESSION_REVOKED,
              actor.userId(),
              AuditEvent.TargetType.USER,
              target.getUserId(),
              actor.ip(),
              Map.of("scope", "single", "sessionRef", sessionRef(sessionId))));
    }
  }

  @Transactional
  public void revokeAllOfUser(Actor actor, Long userId) {
    if (userId.equals(actor.userId())) {
      throw new ApiException(ErrorCode.CANNOT_REVOKE_OWN_SESSIONS);
    }
    users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    int count = sessions.revokeAllOfUser(userId, RevokeReason.ADMIN_REVOKED);
    audit.record(
        AuditEvent.success(
            AuditAction.SESSIONS_REVOKED_ALL,
            actor.userId(),
            AuditEvent.TargetType.USER,
            userId,
            actor.ip(),
            Map.of("scope", "all", "count", count)));
  }

  /** 审计里只记会话的短指纹，不记真实 session id。 */
  static String sessionRef(UUID sessionId) {
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest(sessionId.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
