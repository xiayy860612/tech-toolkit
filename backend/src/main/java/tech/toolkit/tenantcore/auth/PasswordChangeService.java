package tech.toolkit.tenantcore.auth;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.tenantcore.audit.AuditAction;
import tech.toolkit.tenantcore.audit.AuditEvent;
import tech.toolkit.tenantcore.audit.AuditService;
import tech.toolkit.tenantcore.identity.Account;
import tech.toolkit.tenantcore.identity.AccountRepository;
import tech.toolkit.tenantcore.identity.PasswordCredential;
import tech.toolkit.tenantcore.identity.PasswordCredentialRepository;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.PasswordHasher;
import tech.toolkit.tenantcore.platform.PasswordPolicy;

/** 已登录用户修改自己的密码（含强制改密）。成功后撤销该 Account 除当前会话外的全部会话（FR-011b）。 */
@Service
public class PasswordChangeService {

  private final AccountRepository accounts;
  private final PasswordCredentialRepository credentials;
  private final PasswordHasher hasher;
  private final PasswordPolicy policy;
  private final SessionService sessions;
  private final AuditService audit;

  public PasswordChangeService(
      AccountRepository accounts,
      PasswordCredentialRepository credentials,
      PasswordHasher hasher,
      PasswordPolicy policy,
      SessionService sessions,
      AuditService audit) {
    this.accounts = accounts;
    this.credentials = credentials;
    this.hasher = hasher;
    this.policy = policy;
    this.sessions = sessions;
    this.audit = audit;
  }

  @Transactional
  public void change(
      UUID userId,
      UUID accountId,
      UUID currentSessionId,
      String currentPassword,
      String newPassword,
      String ip) {
    Account account =
        accounts.findById(accountId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    PasswordCredential credential =
        credentials.findById(accountId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    if (!hasher.matches(currentPassword, credential.getPasswordHash())) {
      throw new ApiException(ErrorCode.CURRENT_PASSWORD_INCORRECT);
    }
    List<String> problems =
        policy.violations(newPassword, account.getIdentifier(), Set.of(currentPassword));
    if (!problems.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED,
          ErrorCode.VALIDATION_FAILED.title(),
          problems.stream().map(m -> new ApiException.FieldError("newPassword", m)).toList());
    }
    credential.change(hasher.hash(newPassword), false);
    credentials.save(credential);
    sessions.revokeOthersOfAccount(accountId, currentSessionId, RevokeReason.PASSWORD_CHANGED);
    audit.record(
        AuditEvent.success(
            AuditAction.PASSWORD_CHANGED,
            userId,
            accountId,
            AuditEvent.TargetType.ACCOUNT,
            accountId,
            ip,
            Map.of()));
  }
}
