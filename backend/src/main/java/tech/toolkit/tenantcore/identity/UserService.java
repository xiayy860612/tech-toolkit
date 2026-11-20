package tech.toolkit.tenantcore.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.tenantcore.audit.AuditAction;
import tech.toolkit.tenantcore.audit.AuditEvent;
import tech.toolkit.tenantcore.audit.AuditService;
import tech.toolkit.tenantcore.auth.RevokeReason;
import tech.toolkit.tenantcore.auth.SessionService;
import tech.toolkit.tenantcore.platform.Actor;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ApiException.FieldError;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.PageResult;

/** User 的创建、更新、停用/启用。不变量（最后一名管理员、不能停用自己）在事务内用行锁保证；停用时在同一事务内撤销其全部会话并写审计。 */
@Service
public class UserService {

  public record NewUser(
      String displayName,
      String phone,
      String email,
      Role role,
      String username,
      String temporaryPassword) {}

  public record Changes(String displayName, String phone, String email, Role role, long version) {}

  public record Created(UserView user, AccountView account, String temporaryPassword) {}

  private final AppUserRepository users;
  private final AccountRepository accounts;
  private final UserQueryRepository queries;
  private final AccountProvisioner provisioner;
  private final SessionService sessions;
  private final AuditService audit;

  public UserService(
      AppUserRepository users,
      AccountRepository accounts,
      UserQueryRepository queries,
      AccountProvisioner provisioner,
      SessionService sessions,
      AuditService audit) {
    this.users = users;
    this.accounts = accounts;
    this.queries = queries;
    this.provisioner = provisioner;
    this.sessions = sessions;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public PageResult<UserView> list(String keyword, Status status, int page, int size) {
    PageResult<AppUser> found = queries.page(keyword, status, page, size);
    List<UserView> views = queries.views(found.items());
    return new PageResult<>(views, page, size, found.total());
  }

  @Transactional(readOnly = true)
  public UserView get(UUID id) {
    return queries.view(find(id));
  }

  @Transactional
  public Created create(Actor actor, NewUser in) {
    List<FieldError> errors =
        new ArrayList<>(IdentityRules.userFields(in.displayName(), in.phone(), in.email()));
    errors.addAll(
        provisioner.validate(
            "account.username",
            in.username(),
            "account.temporaryPassword",
            in.temporaryPassword()));
    IdentityRules.throwIfAny(errors);

    AppUser user =
        users.save(new AppUser(in.displayName().trim(), in.phone(), in.email(), in.role()));
    AccountProvisioner.Provisioned p =
        provisioner.provision(user.getId(), in.username(), in.temporaryPassword());
    audit.record(
        AuditEvent.success(
            AuditAction.USER_CREATED,
            actor.userId(),
            actor.accountId(),
            AuditEvent.TargetType.USER,
            user.getId(),
            actor.ip(),
            Map.of("role", in.role().name(), "username", in.username())));
    return new Created(
        queries.view(user),
        new AccountView(p.account(), p.mustChangePassword()),
        p.generatedPassword());
  }

  @Transactional
  public UserView update(Actor actor, UUID id, Changes in) {
    IdentityRules.throwIfAny(IdentityRules.userFields(in.displayName(), in.phone(), in.email()));
    AppUser user = find(id);
    if (user.getVersion() == null || user.getVersion() != in.version()) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT);
    }
    boolean demotion = user.getRole() == Role.TENANT_ADMIN && in.role() != Role.TENANT_ADMIN;
    if (demotion && user.getStatus() == Status.ACTIVE) {
      requireAnotherActiveAdmin(user);
    }
    Map<String, Object> changed = new LinkedHashMap<>();
    if (!user.getDisplayName().equals(in.displayName().trim())) {
      changed.put("displayName", true);
    }
    if (!java.util.Objects.equals(user.getPhone(), in.phone())) {
      changed.put("phone", true);
    }
    if (!java.util.Objects.equals(user.getEmail(), in.email())) {
      changed.put("email", true);
    }
    if (user.getRole() != in.role()) {
      changed.put("role", user.getRole().name() + "->" + in.role().name());
    }
    user.setDisplayName(in.displayName().trim());
    user.setPhone(in.phone());
    user.setEmail(in.email());
    user.setRole(in.role());
    try {
      users.saveAndFlush(user);
    } catch (OptimisticLockingFailureException e) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT);
    }
    audit.record(userEvent(AuditAction.USER_UPDATED, actor, id, Map.of("changed", changed)));
    return queries.view(user);
  }

  @Transactional
  public UserView disable(Actor actor, UUID id) {
    if (id.equals(actor.userId())) {
      throw new ApiException(ErrorCode.CANNOT_DISABLE_SELF);
    }
    AppUser user = find(id);
    if (user.getStatus() == Status.ACTIVE) {
      if (user.getRole() == Role.TENANT_ADMIN) {
        requireAnotherActiveAdmin(user);
      }
      user.setStatus(Status.DISABLED);
      users.saveAndFlush(user);
      int revoked = sessions.revokeAllOfUser(id, RevokeReason.USER_DISABLED);
      audit.record(
          userEvent(AuditAction.USER_DISABLED, actor, id, Map.of("revokedSessions", revoked)));
    }
    return queries.view(user);
  }

  @Transactional
  public UserView enable(Actor actor, UUID id) {
    AppUser user = find(id);
    if (user.getStatus() != Status.ACTIVE) {
      user.setStatus(Status.ACTIVE);
      users.saveAndFlush(user);
      audit.record(userEvent(AuditAction.USER_ENABLED, actor, id, Map.of()));
    }
    return queries.view(user);
  }

  /** 先锁住全部可用管理员（固定顺序），再确认除目标外仍有至少一名。 */
  private void requireAnotherActiveAdmin(AppUser target) {
    boolean other =
        users.lockActiveAdmins().stream().anyMatch(a -> !a.getId().equals(target.getId()));
    if (!other) {
      throw new ApiException(ErrorCode.LAST_ADMIN);
    }
  }

  private AppUser find(UUID id) {
    return users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  private static AuditEvent userEvent(
      AuditAction action, Actor actor, UUID target, Map<String, Object> detail) {
    return AuditEvent.success(
        action,
        actor.userId(),
        actor.accountId(),
        AuditEvent.TargetType.USER,
        target,
        actor.ip(),
        detail);
  }
}
