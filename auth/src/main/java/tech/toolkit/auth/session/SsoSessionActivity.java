package tech.toolkit.auth.session;

import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.toolkit.auth.config.AuthProperties;

/**
 * Answers "is this login still alive?" for requests that carry no browser session, such as a client
 * refreshing a token, and records that the login was used (FR-003: a refresh counts as activity).
 * The login is found through its {@code sid}, which is a hash of the session id; the hash is
 * computed in SQL the same way {@link SsoSessionIds#hash} does, among the sessions of one user only
 * (indexed by principal).
 */
@Component
public class SsoSessionActivity {

  /** Same as {@link SsoSessionIds#hash}: SHA-256, base64url, no padding. */
  private static final String SID_OF_ROW =
      "rtrim(translate(encode(sha256(convert_to(session_id, 'UTF8')), 'base64'), '+/', '-_'), '=')";

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final long absoluteMillis;

  public SsoSessionActivity(JdbcTemplate jdbc, Clock clock, AuthProperties properties) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.absoluteMillis = properties.session().absoluteTimeout().toMillis();
  }

  /**
   * True when the login exists and is inside both limits (idle, from its last access; absolute,
   * from its creation); in that case its last access is moved to now, in the same statement.
   */
  public boolean touchIfAlive(String principalName, String sid) {
    if (principalName == null || sid == null) {
      return false;
    }
    long now = clock.millis();
    return jdbc.update(
            "update spring_session set last_access_time = ?,"
                + " expiry_time = ? + max_inactive_interval::bigint * 1000"
                + " where principal_name = ? and "
                + SID_OF_ROW
                + " = ?"
                + " and last_access_time + max_inactive_interval::bigint * 1000 > ?"
                + " and creation_time + ? > ?",
            now,
            now,
            principalName,
            sid,
            now,
            absoluteMillis,
            now)
        > 0;
  }
}
