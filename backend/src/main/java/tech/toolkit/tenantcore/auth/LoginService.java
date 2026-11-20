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
import tech.toolkit.tenantcore.identity.Account;
import tech.toolkit.tenantcore.identity.AccountRepository;
import tech.toolkit.tenantcore.identity.AccountType;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.PasswordCredentialRepository;
import tech.toolkit.tenantcore.identity.Status;
import tech.toolkit.tenantcore.platform.AdminCenterProperties;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.IpAddresses;

/**
 * 用户名密码登录。判定顺序：Account 存在 → Account ACTIVE → User ACTIVE → 未锁定 → 密码匹配；任一失败对外 统一为
 * INVALID_CREDENTIALS（FR-007），真实原因只写审计。失败计数与审计必须先提交再抛错，所以事务 在内部显式控制。
 */
@Service
public class LoginService {

  public record LoggedIn(
      AppUser user, Account account, Session session, String token, boolean mustChangePassword) {
    @Override
    public String toString() {
      return "LoggedIn[redacted]";
    }
  }

  private sealed interface Outcome permits Success, Failure {}

  private record Success(LoggedIn value) implements Outcome {}

  private record Failure() implements Outcome {}

  private final TransactionTemplate tx;
  private final AccountRepository accounts;
  private final AppUserRepository users;
  private final PasswordCredentialRepository credentials;
  private final List<Authenticator> authenticators;
  private final SessionService sessions;
  private final AuditService audit;
  private final AdminCenterProperties.Login config;
  private final Clock clock;

  public LoginService(
      TransactionTemplate tx,
      AccountRepository accounts,
      AppUserRepository users,
      PasswordCredentialRepository credentials,
      List<Authenticator> authenticators,
      SessionService sessions,
      AuditService audit,
      AdminCenterProperties properties,
      Clock clock) {
    this.tx = tx;
    this.accounts = accounts;
    this.users = users;
    this.credentials = credentials;
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
    Authenticator authenticator = authenticatorFor(AccountType.USERNAME_PASSWORD);
    Optional<Account> found =
        accounts.findByTypeAndIdentifierNormalized(
            AccountType.USERNAME_PASSWORD, Account.normalize(username));
    if (found.isEmpty()) {
      authenticator.burnTime(password);
      audit.record(
          AuditEvent.failure(
              AuditAction.LOGIN_FAILURE,
              null,
              null,
              null,
              null,
              "UNKNOWN_USER",
              ip,
              Map.of("username", username)));
      return new Failure();
    }
    Account account = found.get();
    AppUser user = users.findById(account.getUserId()).orElse(null);
    Instant now = clock.instant();

    String rejection = null;
    if (user == null || user.getStatus() != Status.ACTIVE) {
      rejection = "USER_DISABLED";
    } else if (account.getStatus() != Status.ACTIVE) {
      rejection = "ACCOUNT_DISABLED";
    } else if (account.isLocked(now)) {
      rejection = "LOCKED";
    }
    if (rejection != null) {
      authenticator.burnTime(password);
      audit.record(failure(account, rejection, ip));
      return new Failure();
    }

    if (!authenticator.verify(account, password)) {
      registerFailure(account, ip, now);
      return new Failure();
    }

    account.setFailedAttempts(0);
    account.setLockedUntil(null);
    account.setLastLoginAt(now);
    accounts.save(account);
    SessionService.Created created =
        sessions.create(user.getId(), account.getId(), IpAddresses.parse(ip), userAgent);
    audit.record(
        AuditEvent.success(
            AuditAction.LOGIN_SUCCESS,
            user.getId(),
            account.getId(),
            AuditEvent.TargetType.ACCOUNT,
            account.getId(),
            ip,
            Map.of()));
    boolean mustChange =
        credentials.findById(account.getId()).map(c -> c.isMustChangePassword()).orElse(false);
    return new Success(new LoggedIn(user, account, created.session(), created.token(), mustChange));
  }

  private void registerFailure(Account account, String ip, Instant now) {
    int failed = account.getFailedAttempts() + 1;
    boolean lock = failed >= config.maxFailedAttempts();
    account.setFailedAttempts(lock ? 0 : failed);
    if (lock) {
      account.setLockedUntil(now.plus(config.lockDuration()));
    }
    accounts.save(account);
    audit.record(failure(account, "BAD_PASSWORD", ip));
    if (lock) {
      audit.record(
          AuditEvent.failure(
              AuditAction.ACCOUNT_LOCKED,
              account.getUserId(),
              account.getId(),
              AuditEvent.TargetType.ACCOUNT,
              account.getId(),
              "TOO_MANY_FAILURES",
              ip,
              Map.of("lockedUntil", account.getLockedUntil().toString())));
    }
  }

  private AuditEvent failure(Account account, String reason, String ip) {
    return AuditEvent.failure(
        AuditAction.LOGIN_FAILURE,
        null,
        account.getId(),
        AuditEvent.TargetType.ACCOUNT,
        account.getId(),
        reason,
        ip,
        Map.of());
  }

  private Authenticator authenticatorFor(AccountType type) {
    return authenticators.stream()
        .filter(a -> a.type() == type)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No authenticator for " + type));
  }
}
