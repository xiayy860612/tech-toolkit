package tech.toolkit.tenantcore.bootstrap;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.audit.AuditAction;
import tech.toolkit.tenantcore.audit.AuditEvent;
import tech.toolkit.tenantcore.audit.AuditService;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.IdentityRules;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.PasswordHasher;
import tech.toolkit.tenantcore.platform.TemporaryPasswordGenerator;
import tech.toolkit.tenantcore.platform.TenantContext;

/** 预置默认租户（本期恒为 tenant_id = 0）的首位租户管理员。本期不涉及租户管理，因此只创建管理员， 不创建任何租户实体；首次登录强制改密。 */
@Service
public class BootstrapService {

  public record Created(String username, String temporaryPassword) {
    @Override
    public String toString() {
      return "Created[redacted]";
    }
  }

  private final long tenantId;
  private final TransactionTemplate tx;
  private final AppUserRepository users;
  private final PasswordHasher hasher;
  private final TemporaryPasswordGenerator generator;
  private final AuditService audit;

  public BootstrapService(
      @Value("${admin-center.bootstrap.tenant-id:0}") long tenantId,
      TransactionTemplate tx,
      AppUserRepository users,
      PasswordHasher hasher,
      TemporaryPasswordGenerator generator,
      AuditService audit) {
    this.tenantId = tenantId;
    this.tx = tx;
    this.users = users;
    this.hasher = hasher;
    this.generator = generator;
    this.audit = audit;
  }

  /** 创建首位管理员；{@code password} 为空则由系统生成（只返回这一次）。用户名已存在则抛 USERNAME_TAKEN。 */
  public Created createFirstAdmin(String username, String displayName, String password) {
    String temporary = password == null || password.isBlank() ? generator.generate() : password;
    String name = displayName == null || displayName.isBlank() ? username : displayName;
    TenantContext.runAs(
        tenantId,
        () ->
            tx.executeWithoutResult(
                s -> {
                  if (users
                      .findByUsernameNormalized(IdentityRules.normalizeUsername(username))
                      .isPresent()) {
                    throw new ApiException(ErrorCode.USERNAME_TAKEN);
                  }
                  AppUser user =
                      users.save(
                          new AppUser(
                              name, Role.TENANT_ADMIN, username, hasher.hash(temporary), true));
                  audit.record(
                      AuditEvent.success(
                          AuditAction.USER_CREATED,
                          null,
                          AuditEvent.TargetType.USER,
                          user.getId(),
                          null,
                          Map.of("bootstrap", true, "role", Role.TENANT_ADMIN.name())));
                }));
    return new Created(username, temporary);
  }
}
