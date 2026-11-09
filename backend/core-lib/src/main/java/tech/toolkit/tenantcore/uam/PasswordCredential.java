package tech.toolkit.tenantcore.uam;

import java.time.Instant;
import java.util.UUID;

/** The stored password Credential of an Account (hash only) with its lockout state. */
public record PasswordCredential(
    UUID accountId,
    String passwordHash,
    boolean mustChangePassword,
    Instant updatedAt,
    int failedCount,
    Instant lockedUntil) {

  @Override
  public String toString() {
    return "PasswordCredential[accountId="
        + accountId
        + ", mustChangePassword="
        + mustChangePassword
        + "]";
  }
}
