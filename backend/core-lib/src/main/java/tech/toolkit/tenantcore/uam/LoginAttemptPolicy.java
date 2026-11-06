package tech.toolkit.tenantcore.uam;

import java.time.Duration;
import java.time.Instant;

/**
 * Account lockout: after {@code maxFailures} consecutive failures the Account is locked for {@code
 * lockDuration}. When a lock has run out the counter starts again from zero.
 */
public class LoginAttemptPolicy {

  /** The new failure state to store after a failed attempt. */
  public record Failure(int failedCount, Instant lockedUntil) {}

  private final int maxFailures;
  private final Duration lockDuration;

  public LoginAttemptPolicy(int maxFailures, Duration lockDuration) {
    this.maxFailures = maxFailures;
    this.lockDuration = lockDuration;
  }

  public static LoginAttemptPolicy defaults() {
    return new LoginAttemptPolicy(5, Duration.ofMinutes(15));
  }

  public boolean isLocked(PasswordCredential credential, Instant now) {
    return credential.lockedUntil() != null && credential.lockedUntil().isAfter(now);
  }

  public Failure afterFailure(PasswordCredential credential, Instant now) {
    boolean lockExpired =
        credential.lockedUntil() != null && !credential.lockedUntil().isAfter(now);
    int previous = lockExpired ? 0 : credential.failedCount();
    int failed = previous + 1;
    return new Failure(failed, failed >= maxFailures ? now.plus(lockDuration) : null);
  }
}
