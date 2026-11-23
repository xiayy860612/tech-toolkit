package tech.toolkit.auth.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.toolkit.auth.session.SsoSessionIds;
import tech.toolkit.auth.support.PostgresTestBase;

/** What the cleanup deletes, what it must keep, and that only one instance runs it at a time. */
class ExpiredDataCleanupIT extends PostgresTestBase {

  @Autowired ExpiredDataCleanupJob job;

  private final JdbcTemplate db = ownerJdbc();

  private void authorization(
      String id, String codeExpiry, String accessExpiry, String refreshExpiry) {
    db.update(
        "insert into oauth2_authorization (id, registered_client_id, principal_name,"
            + " authorization_grant_type, authorization_code_expires_at, access_token_expires_at,"
            + " refresh_token_expires_at) values (?, 'c', '1', 'authorization_code',"
            + " now() + ?::interval, now() + ?::interval, now() + ?::interval)",
        id,
        codeExpiry,
        accessExpiry,
        refreshExpiry);
  }

  private void session(String sessionId) {
    db.update(
        "insert into spring_session (primary_id, session_id, creation_time, last_access_time,"
            + " max_inactive_interval, expiry_time, principal_name)"
            + " values (?, ?, 0, 0, 1800, 9999999999999, '1')",
        "p-" + sessionId,
        sessionId);
  }

  private void ssoClient(String sessionId) {
    db.update(
        "insert into auth_sso_client (session_id, client_id) values (?, 'client1')",
        SsoSessionIds.hash(sessionId));
  }

  private void delivery(String status, String age) {
    db.update(
        "insert into auth_logout_delivery (client_id, session_id, status, next_attempt_at)"
            + " values ('client1', 's', ?, now() - ?::interval)",
        status,
        age);
  }

  private void usedRefreshToken(String hash, String age) {
    db.update(
        "insert into auth_used_refresh_token (token_hash, authorization_id, used_at)"
            + " values (?, 'a', now() - ?::interval)",
        hash.repeat(64).substring(0, 64),
        age);
  }

  private int count(String table) {
    return db.queryForObject("select count(*) from " + table, Integer.class);
  }

  @Test
  void deletesAuthorizationsWhoseCodeAndTokensAllExpiredMoreThanADayAgo() {
    authorization("old", "-3 days", "-3 days", "-3 days");
    authorization("code-only-old", "-2 days", "-2 days", "+0 seconds");
    db.update("update oauth2_authorization set refresh_token_expires_at = null");
    authorization("refresh-still-valid", "-3 days", "-3 days", "+1 hour");
    authorization("expired-an-hour-ago", "-1 hour", "-1 hour", "-1 hour");
    authorization("access-still-valid", "-3 days", "+5 minutes", "-3 days");

    ExpiredDataCleanupJob.Result result = job.runOnce();

    assertThat(db.queryForList("select id from oauth2_authorization order by id", String.class))
        .containsExactly("access-still-valid", "expired-an-hour-ago", "refresh-still-valid");
    assertThat(result.authorizations()).isEqualTo(2);
  }

  @Test
  void keepsAnAuthorizationThatHasNoExpiryYet() {
    db.update(
        "insert into oauth2_authorization (id, registered_client_id, principal_name,"
            + " authorization_grant_type) values ('pending', 'c', '1', 'authorization_code')");

    job.runOnce();

    assertThat(count("oauth2_authorization")).isEqualTo(1);
  }

  @Test
  void deletesSsoClientRowsOfSessionsThatNoLongerExist() {
    session("live-session");
    ssoClient("live-session");
    ssoClient("gone-session");

    ExpiredDataCleanupJob.Result result = job.runOnce();

    assertThat(db.queryForList("select session_id from auth_sso_client", String.class))
        .containsExactly(SsoSessionIds.hash("live-session"));
    assertThat(result.ssoClients()).isEqualTo(1);
  }

  @Test
  void deletesFinishedDeliveriesOlderThanAWeekOnly() {
    delivery("DELIVERED", "8 days");
    delivery("GAVE_UP", "8 days");
    delivery("DELIVERED", "1 day");
    delivery("PENDING", "8 days");

    ExpiredDataCleanupJob.Result result = job.runOnce();

    assertThat(db.queryForList("select status from auth_logout_delivery order by id", String.class))
        .containsExactly("DELIVERED", "PENDING");
    assertThat(result.deliveries()).isEqualTo(2);
  }

  @Test
  void deletesUsedRefreshTokensOlderThanTheLongestRefreshLifetime() {
    usedRefreshToken("a", "9 hours");
    usedRefreshToken("b", "1 hour");

    ExpiredDataCleanupJob.Result result = job.runOnce();

    assertThat(db.queryForList("select token_hash from auth_used_refresh_token", String.class))
        .containsExactly("b".repeat(64));
    assertThat(result.usedRefreshTokens()).isEqualTo(1);
  }

  @Test
  void neverTouchesTheAuditLog() {
    db.update(
        "insert into auth_audit_event (occurred_at, type, result)"
            + " values (now() - interval '400 days', 'LOGIN_SUCCESS', 'SUCCESS')");

    job.runOnce();

    assertThat(count("auth_audit_event")).isEqualTo(1);
  }

  @Test
  void anInstanceThatFindsAnotherOneAtWorkDoesNothing() throws Exception {
    authorization("old", "-3 days", "-3 days", "-3 days");

    try (Connection other =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      other.setAutoCommit(false);
      try (PreparedStatement lock = other.prepareStatement("select pg_advisory_xact_lock(?)")) {
        lock.setLong(1, ExpiredDataCleanupJob.LOCK_KEY);
        lock.execute();
      }

      ExpiredDataCleanupJob.Result skipped = job.runOnce();

      assertThat(skipped.ran()).isFalse();
      assertThat(count("oauth2_authorization")).isEqualTo(1);
      other.rollback();
    }

    assertThat(job.runOnce().ran()).isTrue();
    assertThat(count("oauth2_authorization")).isZero();
  }
}
