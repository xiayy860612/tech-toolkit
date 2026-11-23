package tech.toolkit.auth.account;

import java.time.Clock;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.auth.audit.AuditEvent;
import tech.toolkit.auth.audit.AuditService;
import tech.toolkit.auth.audit.AuditType;

/**
 * Password changes (FR-012, FR-017, FR-020). Whoever sets a new password, the old credentials stop
 * working everywhere: every authorization is deleted and the user's other logins are ended.
 */
@Service
public class AccountSecurityService {

  /** The outcome of a change the user asked for: done, or what is wrong, in words for the page. */
  public record Change(boolean changed, List<String> problems) {
    static Change done() {
      return new Change(true, List.of());
    }

    static Change refused(List<String> problems) {
      return new Change(false, problems);
    }
  }

  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final PasswordPolicy policy;
  private final UserSessions sessions;
  private final AuditService audit;
  private final Clock clock;

  public AccountSecurityService(
      UserRepository users,
      PasswordEncoder encoder,
      PasswordPolicy policy,
      UserSessions sessions,
      AuditService audit,
      Clock clock) {
    this.users = users;
    this.encoder = encoder;
    this.policy = policy;
    this.sessions = sessions;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * A user changes their own password: needs the current one, and the new one must meet the policy.
   */
  @Transactional
  public Change changeOwnPassword(
      long userId, String currentSessionId, String currentPassword, String newPassword) {
    User user = users.findById(userId).orElseThrow(() -> new IllegalStateException("unknown user"));
    if (currentPassword == null || !encoder.matches(currentPassword, user.passwordHash())) {
      return Change.refused(List.of("当前密码不正确"));
    }
    List<String> problems = policyProblems(newPassword, user.username());
    if (!problems.isEmpty()) {
      return Change.refused(problems);
    }
    applyPassword(userId, newPassword, false, userId, currentSessionId);
    return Change.done();
  }

  /**
   * Sets a password on behalf of someone else (administrator reset, first administrator). The user
   * must change it at the next login, and all their logins end. The caller checks the policy first.
   */
  @Transactional
  public void setPasswordFor(long userId, String password, long actorId) {
    applyPassword(userId, password, true, actorId, null);
  }

  /** The policy rules the password breaks, as text for the user; empty when it is acceptable. */
  public List<String> policyProblems(String password, String username) {
    return policy.check(password, username).stream()
        .map(PasswordPolicy.Violation::message)
        .toList();
  }

  private void applyPassword(
      long userId, String password, boolean mustChange, long actorId, String keepSessionId) {
    users.updatePassword(userId, encoder.encode(password), mustChange, clock.instant());
    sessions.revokeAuthorizations(userId);
    sessions.endLogins(userId, keepSessionId);
    audit.record(
        AuditEvent.of(AuditType.PASSWORD_CHANGED, true)
            .actor(String.valueOf(actorId))
            .subjectUserId(userId));
  }
}
