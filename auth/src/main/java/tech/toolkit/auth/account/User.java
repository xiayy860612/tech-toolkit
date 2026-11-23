package tech.toolkit.auth.account;

import java.time.Instant;

/** A user account row (data-model.md, auth_user). Never printed with its password hash. */
public record User(
    long id,
    String username,
    String passwordHash,
    Status status,
    boolean admin,
    boolean mustChangePassword,
    int failedAttempts,
    Instant lockedUntil,
    Instant passwordChangedAt,
    Instant createdAt,
    Instant updatedAt) {

  public enum Status {
    ENABLED,
    DISABLED
  }

  public boolean enabled() {
    return status == Status.ENABLED;
  }

  @Override
  public String toString() {
    return "User[id=" + id + ", username=" + username + ", status=" + status + "]";
  }
}
