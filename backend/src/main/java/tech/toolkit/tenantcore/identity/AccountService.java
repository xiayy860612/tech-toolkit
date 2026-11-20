package tech.toolkit.tenantcore.identity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.tenantcore.audit.AuditAction;
import tech.toolkit.tenantcore.audit.AuditEvent;
import tech.toolkit.tenantcore.audit.AuditService;
import tech.toolkit.tenantcore.auth.RevokeReason;
import tech.toolkit.tenantcore.auth.SessionService;
import tech.toolkit.tenantcore.platform.Actor;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.PasswordHasher;
import tech.toolkit.tenantcore.platform.PasswordPolicy;
import tech.toolkit.tenantcore.platform.TemporaryPasswordGenerator;

/**
 * User 名下 Account 的新增、停用/启用、解锁、重置密码、删除。停用、删除、重置密码在同一事务内撤销相应会话并写审计； 删除或停用 User 最后一个可用 Account 须显式确认。
 */
@Service
public class AccountService {

  /** {@code temporaryPassword} 仅在系统生成时非空，只返回一次。 */
  public record Created(AccountView account, String temporaryPassword) {
    @Override
    public String toString() {
      return "Created[redacted]";
    }
  }

  private final AppUserRepository users;
  private final AccountRepository accounts;
  private final PasswordCredentialRepository credentials;
  private final AccountProvisioner provisioner;
  private final PasswordHasher hasher;
  private final PasswordPolicy policy;
  private final TemporaryPasswordGenerator generator;
  private final SessionService sessions;
  private final AuditService audit;

  public AccountService(
      AppUserRepository users,
      AccountRepository accounts,
      PasswordCredentialRepository credentials,
      AccountProvisioner provisioner,
      PasswordHasher hasher,
      PasswordPolicy policy,
      TemporaryPasswordGenerator generator,
      SessionService sessions,
      AuditService audit) {
    this.users = users;
    this.accounts = accounts;
    this.credentials = credentials;
    this.provisioner = provisioner;
    this.hasher = hasher;
    this.policy = policy;
    this.generator = generator;
    this.sessions = sessions;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<AccountView> list(UUID userId) {
    requireUser(userId);
    return accounts.findByUserIdOrderByCreatedAtAsc(userId).stream().map(this::view).toList();
  }

  @Transactional
  public Created create(Actor actor, UUID userId, String username, String temporaryPassword) {
    requireUser(userId);
    IdentityRules.throwIfAny(
        provisioner.validate("username", username, "temporaryPassword", temporaryPassword));
    AccountProvisioner.Provisioned p = provisioner.provision(userId, username, temporaryPassword);
    audit.record(
        event(
            AuditAction.ACCOUNT_CREATED, actor, p.account().getId(), Map.of("username", username)));
    return new Created(new AccountView(p.account(), true), p.generatedPassword());
  }

  @Transactional
  public AccountView disable(Actor actor, UUID userId, UUID accountId, boolean confirm) {
    AppUser user = requireUser(userId);
    Account account = requireAccount(userId, accountId);
    if (account.getStatus() == Status.ACTIVE) {
      requireConfirmIfLastAvailable(user, account, confirm);
      account.setStatus(Status.DISABLED);
      accounts.saveAndFlush(account);
      int revoked = sessions.revokeAllOfAccount(accountId, RevokeReason.ACCOUNT_DISABLED);
      audit.record(
          event(
              AuditAction.ACCOUNT_DISABLED, actor, accountId, Map.of("revokedSessions", revoked)));
    }
    return view(account);
  }

  @Transactional
  public AccountView enable(Actor actor, UUID userId, UUID accountId) {
    requireUser(userId);
    Account account = requireAccount(userId, accountId);
    if (account.getStatus() != Status.ACTIVE) {
      account.setStatus(Status.ACTIVE);
      accounts.saveAndFlush(account);
      audit.record(event(AuditAction.ACCOUNT_ENABLED, actor, accountId, Map.of()));
    }
    return view(account);
  }

  @Transactional
  public AccountView unlock(Actor actor, UUID userId, UUID accountId) {
    requireUser(userId);
    Account account = requireAccount(userId, accountId);
    account.setFailedAttempts(0);
    account.setLockedUntil(null);
    accounts.saveAndFlush(account);
    audit.record(event(AuditAction.ACCOUNT_UNLOCKED, actor, accountId, Map.of()));
    return view(account);
  }

  @Transactional
  public Created resetPassword(Actor actor, UUID userId, UUID accountId, String temporaryPassword) {
    requireUser(userId);
    Account account = requireAccount(userId, accountId);
    if (temporaryPassword != null) {
      IdentityRules.throwIfAny(
          policy.violations(temporaryPassword, account.getIdentifier(), java.util.Set.of()).stream()
              .map(m -> new ApiException.FieldError("temporaryPassword", m))
              .toList());
    }
    String password = temporaryPassword == null ? generator.generate() : temporaryPassword;
    PasswordCredential credential =
        credentials.findById(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    credential.change(hasher.hash(password), true);
    credentials.save(credential);
    int revoked = sessions.revokeAllOfAccount(accountId, RevokeReason.PASSWORD_RESET);
    audit.record(
        event(AuditAction.PASSWORD_RESET, actor, accountId, Map.of("revokedSessions", revoked)));
    return new Created(new AccountView(account, true), temporaryPassword == null ? password : null);
  }

  @Transactional
  public void delete(Actor actor, UUID userId, UUID accountId, boolean confirm) {
    AppUser user = requireUser(userId);
    Account account = requireAccount(userId, accountId);
    requireConfirmIfLastAvailable(user, account, confirm);
    credentials.deleteById(accountId);
    credentials.flush();
    accounts.delete(account);
    accounts.flush();
    int revoked = sessions.revokeAllOfAccount(accountId, RevokeReason.ACCOUNT_DELETED);
    audit.record(
        event(
            AuditAction.ACCOUNT_DELETED,
            actor,
            accountId,
            Map.of("username", account.getIdentifier(), "revokedSessions", revoked)));
  }

  /** "可用 Account" = Account 与所属 User 均 ACTIVE；动到最后一个可用 Account 须 confirm=true。 */
  private void requireConfirmIfLastAvailable(AppUser user, Account account, boolean confirm) {
    if (confirm || user.getStatus() != Status.ACTIVE || account.getStatus() != Status.ACTIVE) {
      return;
    }
    boolean otherAvailable =
        accounts.findByUserIdOrderByCreatedAtAsc(user.getId()).stream()
            .anyMatch(a -> !a.getId().equals(account.getId()) && a.getStatus() == Status.ACTIVE);
    if (!otherAvailable) {
      throw new ApiException(ErrorCode.LAST_ACTIVE_ACCOUNT_CONFIRM_REQUIRED);
    }
  }

  private AccountView view(Account account) {
    boolean must =
        credentials
            .findById(account.getId())
            .map(PasswordCredential::isMustChangePassword)
            .orElse(false);
    return new AccountView(account, must);
  }

  private AppUser requireUser(UUID userId) {
    return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  private Account requireAccount(UUID userId, UUID accountId) {
    return accounts
        .findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  private static AuditEvent event(
      AuditAction action, Actor actor, UUID accountId, Map<String, Object> detail) {
    return AuditEvent.success(
        action,
        actor.userId(),
        actor.accountId(),
        AuditEvent.TargetType.ACCOUNT,
        accountId,
        actor.ip(),
        detail);
  }
}
