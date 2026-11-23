package tech.toolkit.auth.cleanup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.auth.config.AuthProperties;

/**
 * Removes what has outlived its use (data-model.md). Spring Session removes its own expired
 * sessions; everything else is cleaned here. All instances run the schedule, but a PostgreSQL
 * advisory lock held for the length of the transaction lets only one of them work at a time. The
 * audit log is never touched. Ages are measured with the database clock, like every other
 * time-based decision on the data.
 */
@Component
public class ExpiredDataCleanupJob {

  /** Arbitrary but fixed: the advisory lock that makes the cleanup run on one instance. */
  public static final long LOCK_KEY = 0x41555448_434C4E50L;

  private static final Logger log = LoggerFactory.getLogger(ExpiredDataCleanupJob.class);

  /** The sid of a stored session, computed the way {@code SsoSessionIds.hash} does. */
  private static final String SID_OF_SESSION =
      "rtrim(translate(encode(sha256(convert_to(s.session_id, 'UTF8')), 'base64'), '+/', '-_'), '=')";

  /** What one run did; {@code ran} is false when another instance held the lock. */
  public record Result(
      boolean ran, int authorizations, int ssoClients, int deliveries, int usedRefreshTokens) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final AuthProperties.Cleanup settings;

  public ExpiredDataCleanupJob(
      JdbcTemplate jdbc, TransactionTemplate transaction, AuthProperties properties) {
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.settings = properties.cleanup();
  }

  @Scheduled(
      initialDelayString = "${auth.cleanup.initial-delay:1m}",
      fixedDelayString = "${auth.cleanup.interval:1h}")
  void scheduled() {
    if (settings.enabled()) {
      runOnce();
    }
  }

  /** Cleans once, unless another instance is cleaning right now. */
  public Result runOnce() {
    Result result =
        transaction.execute(
            status -> {
              Boolean locked =
                  jdbc.queryForObject(
                      "select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
              if (!Boolean.TRUE.equals(locked)) {
                return new Result(false, 0, 0, 0, 0);
              }
              return new Result(
                  true,
                  deleteAuthorizations(),
                  deleteSsoClients(),
                  deleteDeliveries(),
                  deleteUsedRefreshTokens());
            });
    if (result.ran()) {
      log.info(
          "cleanup removed authorizations={} ssoClients={} deliveries={} usedRefreshTokens={}",
          result.authorizations(),
          result.ssoClients(),
          result.deliveries(),
          result.usedRefreshTokens());
    }
    return result;
  }

  /** Code, access token and refresh token all expired before the retention period began. */
  private int deleteAuthorizations() {
    // greatest() ignores nulls and is null only when nothing has an expiry: such rows stay
    return jdbc.update(
        "delete from oauth2_authorization where greatest(authorization_code_expires_at,"
            + " access_token_expires_at, refresh_token_expires_at) < now() - (? * interval '1 second')",
        settings.authorizationRetention().toSeconds());
  }

  /** The login a row belongs to is gone, so no logout notice can ever be due for it. */
  private int deleteSsoClients() {
    return jdbc.update(
        "delete from auth_sso_client c where not exists (select 1 from spring_session s where "
            + SID_OF_SESSION
            + " = c.session_id)");
  }

  /** Finished notices; {@code next_attempt_at} is the time of the last attempt for them. */
  private int deleteDeliveries() {
    return jdbc.update(
        "delete from auth_logout_delivery where status in ('DELIVERED', 'GAVE_UP')"
            + " and next_attempt_at < now() - (? * interval '1 second')",
        settings.deliveryRetention().toSeconds());
  }

  /** Reuse detection only matters while the used refresh token could still be presented. */
  private int deleteUsedRefreshTokens() {
    return jdbc.update(
        "delete from auth_used_refresh_token where used_at < now() - (? * interval '1 second')",
        settings.usedRefreshTokenRetention().toSeconds());
  }
}
