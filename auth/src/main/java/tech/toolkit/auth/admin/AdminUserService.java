package tech.toolkit.auth.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.auth.account.AccountSecurityService;
import tech.toolkit.auth.account.User;
import tech.toolkit.auth.account.UserRepository;
import tech.toolkit.auth.account.UserSessions;
import tech.toolkit.auth.audit.AuditEvent;
import tech.toolkit.auth.audit.AuditService;
import tech.toolkit.auth.audit.AuditType;

/**
 * What an administrator can do to user accounts (FR-019, FR-019a). Whatever ends or weakens a
 * user's access (disable, password reset, forced sign-out) also ends their logins and deletes their
 * authorizations at once, and every change is audited with the administrator who made it.
 */
@Service
public class AdminUserService {

  /** A user as the API shows it; never carries the password hash. */
  public record UserView(
      long id,
      String username,
      String status,
      boolean admin,
      boolean locked,
      boolean mustChangePassword,
      Instant createdAt,
      Instant updatedAt) {}

  /** One page of users. */
  public record UserPage(List<UserView> items, int page, int size, long total) {}

  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final AccountSecurityService accounts;
  private final UserSessions sessions;
  private final AuditService audit;

  public AdminUserService(
      UserRepository users,
      PasswordEncoder encoder,
      AccountSecurityService accounts,
      UserSessions sessions,
      AuditService audit) {
    this.users = users;
    this.encoder = encoder;
    this.accounts = accounts;
    this.sessions = sessions;
    this.audit = audit;
  }

  @Transactional
  public UserView create(String username, String initialPassword, boolean admin, long actorId) {
    String name = UserRepository.normalize(username);
    if (!UserRepository.isValidUsername(name)) {
      throw new AdminException(HttpStatus.BAD_REQUEST, "用户名无效", "用户名须为 3 到 64 位的字母、数字、点、下划线或连字符");
    }
    List<String> problems = accounts.policyProblems(initialPassword, name);
    if (!problems.isEmpty()) {
      throw new AdminException(HttpStatus.BAD_REQUEST, "密码不符合要求", String.join("；", problems));
    }
    long id;
    try {
      id = users.insert(name, encoder.encode(initialPassword), admin, true);
    } catch (DuplicateKeyException e) {
      throw new AdminException(HttpStatus.CONFLICT, "用户名已存在", "已有同名用户（不区分大小写）");
    }
    audit.record(
        AuditEvent.of(AuditType.USER_CREATED, true)
            .actor(String.valueOf(actorId))
            .subjectUserId(id)
            .detail(Map.of("admin", admin)));
    return view(find(id));
  }

  public UserPage list(String usernamePrefix, String status, int page, int size) {
    int pageSize = Math.max(1, Math.min(size, 100));
    int pageNumber = Math.max(0, page);
    User.Status wanted = parseStatus(status);
    List<UserView> items =
        users.findPage(usernamePrefix, wanted, pageNumber * pageSize, pageSize).stream()
            .map(AdminUserService::view)
            .toList();
    return new UserPage(items, pageNumber, pageSize, users.count(usernamePrefix, wanted));
  }

  public UserView get(long id) {
    return view(find(id));
  }

  @Transactional
  public void disable(long id, long actorId) {
    find(id);
    users.setStatus(id, User.Status.DISABLED);
    sessions.revokeAuthorizations(id);
    sessions.endLogins(id, null);
    updated(id, actorId, "disable");
  }

  @Transactional
  public void enable(long id, long actorId) {
    find(id);
    users.setStatus(id, User.Status.ENABLED);
    updated(id, actorId, "enable");
  }

  @Transactional
  public void unlock(long id, long actorId) {
    find(id);
    users.unlock(id);
    updated(id, actorId, "unlock");
  }

  @Transactional
  public void resetPassword(long id, String temporaryPassword, long actorId) {
    User user = find(id);
    List<String> problems = accounts.policyProblems(temporaryPassword, user.username());
    if (!problems.isEmpty()) {
      throw new AdminException(HttpStatus.BAD_REQUEST, "密码不符合要求", String.join("；", problems));
    }
    accounts.setPasswordFor(id, temporaryPassword, actorId);
  }

  @Transactional
  public void forceSignOut(long id, long actorId) {
    find(id);
    sessions.revokeAuthorizations(id);
    sessions.endLogins(id, null);
    updated(id, actorId, "sign-out");
  }

  private User find(long id) {
    return users
        .findById(id)
        .orElseThrow(
            () -> new AdminException(HttpStatus.NOT_FOUND, "用户不存在", "没有 id 为 " + id + " 的用户"));
  }

  private void updated(long id, long actorId, String action) {
    audit.record(
        AuditEvent.of(AuditType.USER_UPDATED, true)
            .actor(String.valueOf(actorId))
            .subjectUserId(id)
            .detail(Map.of("action", action)));
  }

  private static User.Status parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return User.Status.valueOf(status);
    } catch (IllegalArgumentException e) {
      throw new AdminException(HttpStatus.BAD_REQUEST, "状态无效", "status 只能是 ENABLED 或 DISABLED");
    }
  }

  private static UserView view(User user) {
    return new UserView(
        user.id(),
        user.username(),
        user.status().name(),
        user.admin(),
        user.lockedUntil() != null && user.lockedUntil().isAfter(Instant.now()),
        user.mustChangePassword(),
        user.createdAt(),
        user.updatedAt());
  }
}
