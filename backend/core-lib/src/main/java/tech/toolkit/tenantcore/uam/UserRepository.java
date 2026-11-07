package tech.toolkit.tenantcore.uam;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public class UserRepository {

  private static final RowMapper<User> MAPPER =
      (rs, i) ->
          new User(
              rs.getObject("id", UUID.class),
              rs.getString("tenant_user_id"),
              rs.getObject("tenant_id", UUID.class),
              rs.getString("display_name"),
              rs.getString("status"),
              rs.getObject("created_at", OffsetDateTime.class).toInstant());

  private static final String COLUMNS =
      "id, tenant_user_id, tenant_id, display_name, status, created_at";

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(User user) {
    jdbc.sql(
            "INSERT INTO uam_user(id, tenant_user_id, tenant_id, display_name, status, created_at)"
                + " VALUES (:id, :tuid, :tenant, :name, :status, :at)")
        .param("id", user.id())
        .param("tuid", user.tenantUserId())
        .param("tenant", user.tenantId())
        .param("name", user.displayName())
        .param("status", user.status())
        .param("at", OffsetDateTime.ofInstant(user.createdAt(), ZoneOffset.UTC))
        .update();
  }

  public Optional<User> findByTenantAndDisplayName(UUID tenantId, String displayName) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM uam_user WHERE tenant_id = :tenant AND display_name = :name"
                + " ORDER BY created_at, id LIMIT 1")
        .param("tenant", tenantId)
        .param("name", displayName)
        .query(MAPPER)
        .optional();
  }

  public Optional<User> findByTenantUserId(String tenantUserId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM uam_user WHERE tenant_user_id = :tuid")
        .param("tuid", tenantUserId)
        .query(MAPPER)
        .optional();
  }

  public Optional<User> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM uam_user WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  public List<User> listByTenant(UUID tenantId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM uam_user WHERE tenant_id = :tenant ORDER BY created_at, id")
        .param("tenant", tenantId)
        .query(MAPPER)
        .list();
  }

  public List<User> page(int offset, int size) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM uam_user ORDER BY created_at DESC, id LIMIT :size OFFSET :offset")
        .param("size", size)
        .param("offset", offset)
        .query(MAPPER)
        .list();
  }

  public long count() {
    return jdbc.sql("SELECT count(*) FROM uam_user").query(Long.class).single();
  }
}
