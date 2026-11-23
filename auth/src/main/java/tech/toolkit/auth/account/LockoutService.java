package tech.toolkit.auth.account;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.toolkit.auth.config.AuthProperties;

/**
 * Counts failed logins per account and locks it (FR-018). Everything happens in single SQL
 * statements on the database's clock, so it stays exact with several instances running: two
 * failures arriving at the same moment are both counted, and only one of them causes the lock.
 */
@Service
public class LockoutService {

  /** What recording a failure led to. */
  public record Outcome(int failedAttempts, boolean locked, boolean newlyLocked) {}

  private final JdbcTemplate jdbc;
  private final AuthProperties.Lockout settings;

  @Autowired
  public LockoutService(JdbcTemplate jdbc, AuthProperties properties) {
    this(jdbc, properties.lockout());
  }

  public LockoutService(JdbcTemplate jdbc, AuthProperties.Lockout settings) {
    this.jdbc = jdbc;
    this.settings = settings;
  }

  /**
   * Counts one failed login. A failure while the account is locked changes nothing (the lock is not
   * extended); a failure after a lock has expired starts counting from one again.
   */
  public Outcome recordFailure(long userId) {
    return jdbc.queryForObject(
        "with before as (select id, locked_until as old_locked from auth_user where id = ? for update)"
            + " update auth_user u set"
            + " failed_attempts = case"
            + "   when u.locked_until > now() then u.failed_attempts"
            + "   when u.locked_until is not null then 1"
            + "   else u.failed_attempts + 1 end,"
            + " locked_until = case"
            + "   when u.locked_until > now() then u.locked_until"
            + "   when (case when u.locked_until is not null then 1 else u.failed_attempts + 1 end) >= ?"
            + "     then now() + (? * interval '1 second')"
            + "   else null end,"
            + " updated_at = now()"
            + " from before b where u.id = b.id"
            + " returning u.failed_attempts,"
            + "   (u.locked_until is not null and u.locked_until > now()) as locked,"
            + "   (b.old_locked is null or b.old_locked <= now()) as was_open",
        (rs, row) -> {
          boolean locked = rs.getBoolean("locked");
          return new Outcome(
              rs.getInt("failed_attempts"), locked, locked && rs.getBoolean("was_open"));
        },
        userId,
        settings.maxAttempts(),
        settings.duration().toSeconds());
  }

  /** A good login clears the counter and any expired lock. */
  public void recordSuccess(long userId) {
    jdbc.update(
        "update auth_user set failed_attempts = 0, locked_until = null, updated_at = now()"
            + " where id = ? and (failed_attempts <> 0 or locked_until is not null)",
        userId);
  }

  /** Whether the account is locked right now, by the database's clock. */
  public boolean isLocked(long userId) {
    Boolean locked =
        jdbc.queryForObject(
            "select coalesce(locked_until > now(), false) from auth_user where id = ?",
            Boolean.class,
            userId);
    return Boolean.TRUE.equals(locked);
  }
}
