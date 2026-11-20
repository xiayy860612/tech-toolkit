package tech.toolkit.tenantcore.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tech.toolkit.tenantcore.identity.AccountType;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.identity.Status;
import tech.toolkit.tenantcore.identity.UserView;
import tech.toolkit.tenantcore.platform.PageResult;

/** /users 的请求与响应（与 contracts/openapi.yaml 一致）；字段取值规则在 IdentityRules，由服务层统一校验。 */
public final class UserDtos {

  private UserDtos() {}

  public record NewAccountSpec(
      @NotNull AccountType type, @NotNull String username, String temporaryPassword) {}

  public record CreateUserRequest(
      @NotNull String displayName,
      String phone,
      String email,
      @NotNull Role role,
      @NotNull @Valid NewAccountSpec account) {}

  public record UpdateUserRequest(
      @NotNull String displayName,
      String phone,
      String email,
      @NotNull Role role,
      @NotNull Long version) {}

  public record User(
      UUID id,
      String displayName,
      String phone,
      String email,
      Role role,
      Status status,
      String primaryUsername,
      int accountCount,
      Instant lastLoginAt,
      long version,
      Instant createdAt) {

    static User of(UserView v) {
      var u = v.user();
      return new User(
          u.getId(),
          u.getDisplayName(),
          u.getPhone(),
          u.getEmail(),
          u.getRole(),
          u.getStatus(),
          v.primaryUsername(),
          v.accountCount(),
          v.lastLoginAt(),
          u.getVersion(),
          u.getCreatedAt());
    }
  }

  public record UserPage(List<User> items, int page, int size, long total) {

    static UserPage of(PageResult<UserView> p) {
      return new UserPage(p.items().stream().map(User::of).toList(), p.page(), p.size(), p.total());
    }
  }

  public record CreateUserResult(User user, AccountDtos.AccountWithTemporaryPassword account) {}
}
