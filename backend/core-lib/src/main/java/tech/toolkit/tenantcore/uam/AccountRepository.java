package tech.toolkit.tenantcore.uam;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Persists Accounts in uam_account; {@code username} is stored in the {@code subject} column. */
public class AccountRepository {

  private static final RowMapper<Account> MAPPER =
      (rs, i) ->
          new Account(
              rs.getObject("id", UUID.class),
              rs.getObject("user_id", UUID.class),
              rs.getObject("tenant_id", UUID.class),
              rs.getString("type"),
              rs.getString("subject"),
              rs.getObject("created_at", OffsetDateTime.class).toInstant());

  private static final String COLUMNS = "id, user_id, tenant_id, type, subject, created_at";

  private final JdbcClient jdbc;

  public AccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Account account) {
    jdbc.sql(
            "INSERT INTO uam_account(id, user_id, tenant_id, type, provider, subject, created_at)"
                + " VALUES (:id, :user, :tenant, :type, '', :subject, :at)")
        .param("id", account.id())
        .param("user", account.userId())
        .param("tenant", account.tenantId())
        .param("type", account.type())
        .param("subject", account.username())
        .param("at", OffsetDateTime.ofInstant(account.createdAt(), ZoneOffset.UTC))
        .update();
  }

  public Optional<Account> findByTenantAndUsername(UUID tenantId, String type, String username) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM uam_account WHERE tenant_id = :tenant AND type = :type AND provider = ''"
                + " AND subject = :subject")
        .param("tenant", tenantId)
        .param("type", type)
        .param("subject", username)
        .query(MAPPER)
        .optional();
  }

  public Optional<Account> findByUserAndType(UUID userId, String type) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM uam_account WHERE user_id = :user AND type = :type")
        .param("user", userId)
        .param("type", type)
        .query(MAPPER)
        .optional();
  }

  public Optional<Account> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM uam_account WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  public List<Account> findByUsers(Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM uam_account WHERE user_id IN (:ids) ORDER BY subject")
        .param("ids", userIds)
        .query(MAPPER)
        .list();
  }
}
