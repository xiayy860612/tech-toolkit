package tech.toolkit.tenantcore.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.audit.AuditAction;
import tech.toolkit.tenantcore.audit.AuditEvent;
import tech.toolkit.tenantcore.audit.AuditService;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.IdentityRules;
import tech.toolkit.tenantcore.identity.Status;
import tech.toolkit.tenantcore.platform.AdminCenterProperties;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.IpAddresses;

/**
 * 用户名密码登录。判定顺序：用户存在 → ACTIVE → 未锁定 → 密码匹配；任一失败对外统一为 INVALID_CREDENTIALS（FR-007），真实原因只写审计。
 * 失败计数与审计必须先提交再抛错，所以事务在内部显式控制。
 */
@Service
public class LoginService {

  public record LoggedIn(AppUser user, Session session, String token, boolean mustChangePassword) {
    @Override
    public String toString() {
      return "LoggedIn[redacted]";
    }
  }

  private sealed interface Outcome permits Success, Failure {}

  private record Success(LoggedIn value) implements Outcome {}

  private record Failure() implements Outcome {}

  private final TransactionTemplate tx;
  private final AppUserRepository users;
  private final List<Authenticator> authenticators;
  private final SessionService sessions;
  private final AuditService audit;
  private final AdminCenterProperties.Login config;
  private final Clock clock;

  public LoginService(
      TransactionTemplate tx,
      AppUserRepository users,
      List<Authenticator> authenticators,
      SessionService sessions,
      AuditService audit,
      AdminCenterProperties properties,
      Clock clock) {
    this.tx = tx;
    this.users = users;
    this.authenticators = authenticators;
    this.sessions = sessions;
    this.audit = audit;
    this.config = properties.login();
    this.clock = clock;
  }

  public LoggedIn login(String username, String password, String clientIp, String userAgent) {
    Outcome outcome = tx.execute(s -> attempt(username, password, clientIp, userAgent));
    if (outcome instanceof Success success) {
      return success.value();
    }
    throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
  }

  private Outcome attempt(String username, String password, String ip, String userAgent) {
    Authenticator authenticator = authenticatorFor(LoginType.USERNAME_PASSWORD);
    Optional<AppUser> found =
        users.findByUsernameNormalized(IdentityRules.normalizeUsername(username));
    if (found.isEmpty()) {
      authenticator.burnTime(password);
      audit.record(
          AuditEvent.failure(
              AuditAction.LOGIN_FAILURE,
              null,
              null,
              null,
              "UNKNOWN_USER",
              ip,
              Map.of("username", username)));
      return new Failure();
    }
    AppUser user = found.get();
    Instant now = clock.instant();

    String rejection = null;
    if (user.getStatus() != Status.ACTIVE) {
      rejection = "USER_DISABLED";
    } else if (user.isLocked(now)) {
      rejection = "LOCKED";
    }
    if (rejection != null) {
      authenticator.burnTime(password);
      audit.record(failure(user, rejection, ip));
      return new Failure();
    }

    if (!authenticator.verify(user, password)) {
      registerFailure(user, ip, now);
      return new Failure();
    }

    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    user.setLastLoginAt(now);
    users.save(user);
    SessionService.Created created =
        sessions.create(
            user.getId(), LoginType.USERNAME_PASSWORD, IpAddresses.parse(ip), userAgent);
    audit.record(
        AuditEvent.success(
            AuditAction.LOGIN_SUCCESS,
            user.getId(),
            AuditEvent.TargetType.USER,
            user.getId(),
            ip,
            Map.of()));
    return new Success(
        new LoggedIn(user, created.session(), created.token(), user.isMustChangePassword()));
  }

  private void registerFailure(AppUser user, String ip, Instant now) {
    int failed = user.getFailedAttempts() + 1;
    boolean lock = failed >= config.maxFailedAttempts();
    user.setFailedAttempts(lock ? 0 : failed);
    if (lock) {
      user.setLockedUntil(now.plus(config.lockDuration()));
    }
    users.save(user);
    audit.record(failure(user, "BAD_PASSWORD", ip));
    if (lock) {
      audit.record(
          AuditEvent.failure(
              AuditAction.USER_LOCKED,
              user.getId(),
              AuditEvent.TargetType.USER,
              user.getId(),
              "TOO_MANY_FAILURES",
              ip,
              Map.of("lockedUntil", user.getLockedUntil().toString())));
    }
  }

  private AuditEvent failure(AppUser user, String reason, String ip) {
    return AuditEvent.failure(
        AuditAction.LOGIN_FAILURE,
        null,
        AuditEvent.TargetType.USER,
        user.getId(),
        reason,
        ip,
        Map.of());
  }

  private Authenticator authenticatorFor(LoginType type) {
    return authenticators.stream()
        .filter(a -> a.type() == type)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No authenticator for " + type));
  }
}
