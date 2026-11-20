package tech.toolkit.tenantcore.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import tech.toolkit.tenantcore.identity.AccountType;
import tech.toolkit.tenantcore.identity.AccountView;
import tech.toolkit.tenantcore.identity.Status;

/** /users/{userId}/accounts 的请求与响应（与 contracts/openapi.yaml 一致）。 */
public final class AccountDtos {

  private AccountDtos() {}

  public record Account(
      UUID id,
      UUID userId,
      AccountType type,
      String identifier,
      Status status,
      boolean locked,
      Instant lockedUntil,
      boolean mustChangePassword,
      Instant lastLoginAt,
      long version,
      Instant createdAt) {

    static Account of(AccountView v, Instant now) {
      var a = v.account();
      boolean locked = a.isLocked(now);
      return new Account(
          a.getId(),
          a.getUserId(),
          a.getType(),
          a.getIdentifier(),
          a.getStatus(),
          locked,
          locked ? a.getLockedUntil() : null,
          v.mustChangePassword(),
          a.getLastLoginAt(),
          a.getVersion(),
          a.getCreatedAt());
    }
  }

  public record AccountWithTemporaryPassword(
      UUID id,
      UUID userId,
      AccountType type,
      String identifier,
      Status status,
      boolean locked,
      Instant lockedUntil,
      boolean mustChangePassword,
      Instant lastLoginAt,
      long version,
      Instant createdAt,
      String temporaryPassword) {
    @Override
    public String toString() {
      return "AccountWithTemporaryPassword[redacted]";
    }

    static AccountWithTemporaryPassword of(AccountView v, String temporaryPassword, Instant now) {
      Account a = Account.of(v, now);
      return new AccountWithTemporaryPassword(
          a.id(),
          a.userId(),
          a.type(),
          a.identifier(),
          a.status(),
          a.locked(),
          a.lockedUntil(),
          a.mustChangePassword(),
          a.lastLoginAt(),
          a.version(),
          a.createdAt(),
          temporaryPassword);
    }
  }

  public record CreateAccountRequest(
      @NotNull AccountType type, @NotNull String username, String temporaryPassword) {
    @Override
    public String toString() {
      return "CreateAccountRequest[redacted]";
    }
  }

  public record ResetPasswordRequest(String temporaryPassword) {
    @Override
    public String toString() {
      return "ResetPasswordRequest[redacted]";
    }
  }
}
