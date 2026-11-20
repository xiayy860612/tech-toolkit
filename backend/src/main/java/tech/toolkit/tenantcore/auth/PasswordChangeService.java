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
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.PasswordHasher;
import tech.toolkit.tenantcore.platform.PasswordPolicy;

/** 已登录用户修改自己的密码（含强制改密）。成功后撤销该用户除当前会话外的全部会话（FR-011b）。 */
@Service
public class PasswordChangeService {

  private final AppUserRepository users;
  private final PasswordHasher hasher;
  private final PasswordPolicy policy;
  private final SessionService sessions;
  private final AuditService audit;

  public PasswordChangeService(
      AppUserRepository users,
      PasswordHasher hasher,
      PasswordPolicy policy,
      SessionService sessions,
      AuditService audit) {
    this.users = users;
    this.hasher = hasher;
    this.policy = policy;
    this.sessions = sessions;
    this.audit = audit;
  }

  @Transactional
  public void change(
      Long userId, UUID currentSessionId, String currentPassword, String newPassword, String ip) {
    AppUser user =
        users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    if (!hasher.matches(currentPassword, user.getPasswordHash())) {
      throw new ApiException(ErrorCode.CURRENT_PASSWORD_INCORRECT);
    }
    List<String> problems =
        policy.violations(newPassword, user.getUsername(), Set.of(currentPassword));
    if (!problems.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED,
          ErrorCode.VALIDATION_FAILED.title(),
          problems.stream().map(m -> new ApiException.FieldError("newPassword", m)).toList());
    }
    user.changePassword(hasher.hash(newPassword), false);
    users.save(user);
    sessions.revokeOthersOfUser(userId, currentSessionId, RevokeReason.PASSWORD_CHANGED);
    audit.record(
        AuditEvent.success(
            AuditAction.PASSWORD_CHANGED,
            userId,
            AuditEvent.TargetType.USER,
            userId,
            ip,
            Map.of()));
  }
}
