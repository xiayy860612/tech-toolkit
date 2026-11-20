package tech.toolkit.tenantcore.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.identity.Status;
import tech.toolkit.tenantcore.identity.UserView;
import tech.toolkit.tenantcore.platform.PageResult;

/** /users 的请求与响应（与 contracts/openapi.yaml 一致）；字段取值规则在 IdentityRules，由服务层统一校验。 */
public final class UserDtos {

  private UserDtos() {}

  public record CreateUserRequest(
      @NotNull String displayName,
      @NotNull Role role,
      @NotNull String username,
      String temporaryPassword) {
    @Override
    public String toString() {
      return "CreateUserRequest[redacted]";
    }
  }

  public record UpdateUserRequest(
      @NotNull String displayName, @NotNull Role role, @NotNull Long version) {}

  public record ResetPasswordRequest(String temporaryPassword) {
    @Override
    public String toString() {
      return "ResetPasswordRequest[redacted]";
    }
  }

  public record User(
      Long id,
      String displayName,
      String username,
      Role role,
      Status status,
      boolean locked,
      Instant lockedUntil,
      boolean mustChangePassword,
      Instant lastLoginAt,
      long version,
      Instant createdAt) {

    static User of(UserView v, Instant now) {
      var u = v.user();
      boolean locked = u.isLocked(now);
      return new User(
          u.getId(),
          u.getDisplayName(),
          u.getUsername(),
          u.getRole(),
          u.getStatus(),
          locked,
          locked ? u.getLockedUntil() : null,
          u.isMustChangePassword(),
          u.getLastLoginAt(),
          u.getVersion(),
          u.getCreatedAt());
    }
  }

  public record UserPage(List<User> items, int page, int size, long total) {

    static UserPage of(PageResult<UserView> p, Instant now) {
      return new UserPage(
          p.items().stream().map(v -> User.of(v, now)).toList(), p.page(), p.size(), p.total());
    }
  }

  public record UserWithTemporaryPassword(User user, String temporaryPassword) {
    @Override
    public String toString() {
      return "UserWithTemporaryPassword[redacted]";
    }
  }
}
