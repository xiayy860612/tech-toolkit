package tech.toolkit.auth.account;

import org.springframework.stereotype.Component;
import tech.toolkit.auth.audit.AuditEvent;
import tech.toolkit.auth.audit.AuditService;
import tech.toolkit.auth.audit.AuditType;

/**
 * Reacts to the outcome of a password login: counts failures, locks, clears, and audits the lock.
 */
@Component
public class LoginAttemptHandler {

  private final UserRepository users;
  private final LockoutService lockout;
  private final AuditService audit;

  public LoginAttemptHandler(UserRepository users, LockoutService lockout, AuditService audit) {
    this.users = users;
    this.lockout = lockout;
    this.audit = audit;
  }

  /** A wrong password for the typed username; an unknown username changes nothing. */
  public void onFailure(String typedUsername) {
    users
        .findByUsername(typedUsername)
        .ifPresent(
            user -> {
              LockoutService.Outcome outcome = lockout.recordFailure(user.id());
              if (outcome.newlyLocked()) {
                audit.record(
                    AuditEvent.of(AuditType.ACCOUNT_LOCKED, true).subjectUserId(user.id()));
              }
            });
  }

  public void onSuccess(long userId) {
    lockout.recordSuccess(userId);
  }

  public boolean isLocked(long userId) {
    return lockout.isLocked(userId);
  }
}
