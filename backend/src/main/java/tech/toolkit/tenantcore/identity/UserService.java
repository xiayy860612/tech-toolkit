package tech.toolkit.tenantcore.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
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
import tech.toolkit.tenantcore.platform.PasswordHasher;
import tech.toolkit.tenantcore.platform.PasswordPolicy;
import tech.toolkit.tenantcore.platform.TemporaryPasswordGenerator;

/**
 * User 的创建、更新、停用/启用、解锁与重置密码。用户名密码内置于 User（FR-001）。不变量（最后一名管理员、不能停用自己、不能改自己的角色）在事务内用行锁保证；
 * 停用或重置密码时在同一事务内撤销其全部会话并写审计。
 */
@Service
public class UserService {

  public record NewUser(String displayName, Role role, String username, String temporaryPassword) {
    @Override
    public String toString() {
      return "NewUser[redacted]";
    }
  }

  public record Changes(String displayName, Role role, long version) {}

  /** {@code temporaryPassword} 仅在系统生成时非空，只返回一次。 */
  public record Created(UserView user, String temporaryPassword) {
    @Override
    public String toString() {
      return "Created[redacted]";
    }
  }

  private final AppUserRepository users;
  private final UserQueryRepository queries;
  private final PasswordHasher hasher;
  private final PasswordPolicy policy;
  private final TemporaryPasswordGenerator generator;
  private final SessionService sessions;
  private final AuditService audit;

  public UserService(
      AppUserRepository users,
      UserQueryRepository queries,
      PasswordHasher hasher,
      PasswordPolicy policy,
      TemporaryPasswordGenerator generator,
      SessionService sessions,
      AuditService audit) {
    this.users = users;
    this.queries = queries;
    this.hasher = hasher;
    this.policy = policy;
    this.generator = generator;
    this.sessions = sessions;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public PageResult<UserView> list(String keyword, Status status, int page, int size) {
    PageResult<AppUser> found = queries.page(keyword, status, page, size);
    return new PageResult<>(
        found.items().stream().map(UserView::new).toList(), page, size, found.total());
  }

  @Transactional(readOnly = true)
  public UserView get(Long id) {
    return new UserView(find(id));
  }

  @Transactional
  public Created create(Actor actor, NewUser in) {
    List<FieldError> errors = new ArrayList<>(IdentityRules.userFields(in.displayName()));
    FieldError badName = IdentityRules.usernameField("username", in.username());
    if (badName != null) {
      errors.add(badName);
    }
    if (in.temporaryPassword() != null) {
      policy.violations(in.temporaryPassword(), in.username(), Set.of()).stream()
          .map(m -> new FieldError("temporaryPassword", m))
          .forEach(errors::add);
    }
    IdentityRules.throwIfAny(errors);

    String normalized = IdentityRules.normalizeUsername(in.username());
    if (users.findByUsernameNormalized(normalized).isPresent()) {
      throw new ApiException(ErrorCode.USERNAME_TAKEN);
    }
    String password =
        in.temporaryPassword() == null ? generator.generate() : in.temporaryPassword();
    AppUser user;
    try {
      user =
          users.saveAndFlush(
              new AppUser(
                  in.displayName().trim(), in.role(), in.username(), hasher.hash(password), true));
    } catch (DataIntegrityViolationException e) {
      throw new ApiException(ErrorCode.USERNAME_TAKEN);
    }
    audit.record(
        userEvent(
            AuditAction.USER_CREATED,
            actor,
            user.getId(),
            Map.of("role", in.role().name(), "username", in.username())));
    return new Created(new UserView(user), in.temporaryPassword() == null ? password : null);
  }

  @Transactional
  public UserView update(Actor actor, Long id, Changes in) {
    IdentityRules.throwIfAny(IdentityRules.userFields(in.displayName()));
    AppUser user = find(id);
    if (user.getVersion() == null || user.getVersion() != in.version()) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT);
    }
    if (id.equals(actor.userId()) && user.getRole() != in.role()) {
      throw new ApiException(ErrorCode.CANNOT_CHANGE_OWN_ROLE);
    }
    boolean demotion = user.getRole() == Role.TENANT_ADMIN && in.role() != Role.TENANT_ADMIN;
    if (demotion && user.getStatus() == Status.ACTIVE) {
      requireAnotherActiveAdmin(user);
    }
    Map<String, Object> changed = new LinkedHashMap<>();
    if (!user.getDisplayName().equals(in.displayName().trim())) {
      changed.put("displayName", true);
    }
    if (user.getRole() != in.role()) {
      changed.put("role", user.getRole().name() + "->" + in.role().name());
    }
    user.setDisplayName(in.displayName().trim());
    user.setRole(in.role());
    try {
      users.saveAndFlush(user);
    } catch (OptimisticLockingFailureException e) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT);
    }
    audit.record(userEvent(AuditAction.USER_UPDATED, actor, id, Map.of("changed", changed)));
    return new UserView(user);
  }

  @Transactional
  public UserView disable(Actor actor, Long id) {
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
    return new UserView(user);
  }

  @Transactional
  public UserView enable(Actor actor, Long id) {
    AppUser user = find(id);
    if (user.getStatus() != Status.ACTIVE) {
      user.setStatus(Status.ACTIVE);
      users.saveAndFlush(user);
      audit.record(userEvent(AuditAction.USER_ENABLED, actor, id, Map.of()));
    }
    return new UserView(user);
  }

  /** 手动解锁用户名密码登录：清零失败计数与锁定时间（FR-008a）。 */
  @Transactional
  public UserView unlock(Actor actor, Long id) {
    AppUser user = find(id);
    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    users.saveAndFlush(user);
    audit.record(userEvent(AuditAction.USER_UNLOCKED, actor, id, Map.of()));
    return new UserView(user);
  }

  /** 重置为临时密码：旧密码失效、该用户全部会话撤销、下次登录强制改密；可重置自己的密码。 */
  @Transactional
  public Created resetPassword(Actor actor, Long id, String temporaryPassword) {
    AppUser user = find(id);
    if (temporaryPassword != null) {
      IdentityRules.throwIfAny(
          policy.violations(temporaryPassword, user.getUsername(), Set.of()).stream()
              .map(m -> new FieldError("temporaryPassword", m))
              .toList());
    }
    String password = temporaryPassword == null ? generator.generate() : temporaryPassword;
    user.changePassword(hasher.hash(password), true);
    users.saveAndFlush(user);
    int revoked = sessions.revokeAllOfUser(id, RevokeReason.PASSWORD_RESET);
    audit.record(
        userEvent(AuditAction.PASSWORD_RESET, actor, id, Map.of("revokedSessions", revoked)));
    return new Created(new UserView(user), temporaryPassword == null ? password : null);
  }

  /** 先锁住全部可用管理员（固定顺序），再确认除目标外仍有至少一名。 */
  private void requireAnotherActiveAdmin(AppUser target) {
    boolean other =
        users.lockActiveAdmins().stream().anyMatch(a -> !a.getId().equals(target.getId()));
    if (!other) {
      throw new ApiException(ErrorCode.LAST_ADMIN);
    }
  }

  private AppUser find(Long id) {
    return users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  private static AuditEvent userEvent(
      AuditAction action, Actor actor, Long target, Map<String, Object> detail) {
    return AuditEvent.success(
        action, actor.userId(), AuditEvent.TargetType.USER, target, actor.ip(), detail);
  }
}
