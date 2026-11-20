package tech.toolkit.tenantcore.api.security;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.auth.Session;
import tech.toolkit.tenantcore.auth.SessionService;
import tech.toolkit.tenantcore.identity.Account;
import tech.toolkit.tenantcore.identity.AccountRepository;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.PasswordCredentialRepository;
import tech.toolkit.tenantcore.identity.Status;
import tech.toolkit.tenantcore.platform.InternalJwtClaims;

/**
 * 把已验签的 USER 令牌落到数据库状态：会话 {@code sid} 必须有效且与声明一致，User 与 Account 均 ACTIVE；
 * 角色与强制改密标志取自数据库（不信任令牌）。须在已设置租户上下文后调用。
 */
@Component
class PrincipalLoader {

  private final TransactionTemplate tx;
  private final SessionService sessions;
  private final AppUserRepository users;
  private final AccountRepository accounts;
  private final PasswordCredentialRepository credentials;

  PrincipalLoader(
      TransactionTemplate tx,
      SessionService sessions,
      AppUserRepository users,
      AccountRepository accounts,
      PasswordCredentialRepository credentials) {
    this.tx = tx;
    this.sessions = sessions;
    this.users = users;
    this.accounts = accounts;
    this.credentials = credentials;
  }

  Optional<AuthenticatedUser> load(InternalJwtClaims claims) {
    return tx.execute(status -> doLoad(claims));
  }

  private Optional<AuthenticatedUser> doLoad(InternalJwtClaims claims) {
    Optional<Session> session = sessions.validate(claims.sessionId());
    if (session.isEmpty()) {
      return Optional.empty();
    }
    Session s = session.get();
    if (!s.getUserId().equals(claims.userId())
        || !s.getAccountId().equals(claims.accountId())
        || s.getTenantId() != claims.tenantId()) {
      return Optional.empty();
    }
    Optional<AppUser> user = users.findById(claims.userId());
    Optional<Account> account = accounts.findById(claims.accountId());
    if (user.isEmpty()
        || account.isEmpty()
        || user.get().getStatus() != Status.ACTIVE
        || account.get().getStatus() != Status.ACTIVE
        || !account.get().getUserId().equals(user.get().getId())) {
      return Optional.empty();
    }
    boolean mustChange =
        credentials
            .findById(account.get().getId())
            .map(c -> c.isMustChangePassword())
            .orElse(false);
    return Optional.of(
        new AuthenticatedUser(
            user.get().getId(),
            account.get().getId(),
            s.getId(),
            claims.tenantId(),
            user.get().getRole(),
            mustChange,
            claims.clientIp()));
  }
}
