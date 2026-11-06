package tech.toolkit.tenantcore.uam;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class PasswordCredentialRepository {

  private final JdbcClient jdbc;

  public PasswordCredentialRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime at(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  public void insert(PasswordCredential credential) {
    jdbc.sql(
            "INSERT INTO uam_credential_password(account_id, password_hash, must_change_password,"
                + " updated_at, failed_count, locked_until)"
                + " VALUES (:account, :hash, :must, :at, :failed, :locked)")
        .param("account", credential.accountId())
        .param("hash", credential.passwordHash())
        .param("must", credential.mustChangePassword())
        .param("at", at(credential.updatedAt()))
        .param("failed", credential.failedCount())
        .param("locked", at(credential.lockedUntil()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .update();
  }

  public Optional<PasswordCredential> findByAccountId(UUID accountId) {
    return jdbc.sql(
            "SELECT account_id, password_hash, must_change_password, updated_at, failed_count,"
                + " locked_until FROM uam_credential_password WHERE account_id = :account")
        .param("account", accountId)
        .query(
            (rs, i) -> {
              OffsetDateTime locked = rs.getObject("locked_until", OffsetDateTime.class);
              return new PasswordCredential(
                  rs.getObject("account_id", UUID.class),
                  rs.getString("password_hash"),
                  rs.getBoolean("must_change_password"),
                  rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                  rs.getInt("failed_count"),
                  locked == null ? null : locked.toInstant());
            })
        .optional();
  }

  public void markFailure(UUID accountId, int failedCount, Instant lockedUntil) {
    jdbc.sql(
            "UPDATE uam_credential_password SET failed_count = :failed, locked_until = :locked"
                + " WHERE account_id = :account")
        .param("failed", failedCount)
        .param("locked", at(lockedUntil), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .param("account", accountId)
        .update();
  }

  public void clearFailures(UUID accountId) {
    jdbc.sql(
            "UPDATE uam_credential_password SET failed_count = 0, locked_until = NULL"
                + " WHERE account_id = :account")
        .param("account", accountId)
        .update();
  }

  public void changePassword(UUID accountId, String passwordHash, Instant now) {
    jdbc.sql(
            "UPDATE uam_credential_password SET password_hash = :hash, must_change_password = false,"
                + " updated_at = :at, failed_count = 0, locked_until = NULL"
                + " WHERE account_id = :account")
        .param("hash", passwordHash)
        .param("at", at(now))
        .param("account", accountId)
        .update();
  }
}
