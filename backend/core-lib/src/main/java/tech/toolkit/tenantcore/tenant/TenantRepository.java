package tech.toolkit.tenantcore.tenant;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public class TenantRepository {

  private static final RowMapper<Tenant> MAPPER =
      (rs, i) ->
          new Tenant(
              rs.getObject("id", UUID.class),
              rs.getString("slug"),
              rs.getString("name"),
              rs.getString("status"),
              rs.getObject("created_at", OffsetDateTime.class).toInstant());

  private static final String COLUMNS = "id, slug, name, status, created_at";

  private final JdbcClient jdbc;

  public TenantRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Tenant tenant) {
    jdbc.sql(
            "INSERT INTO core_tenant(id, slug, name, status, created_at)"
                + " VALUES (:id, :slug, :name, :status, :at)")
        .param("id", tenant.id())
        .param("slug", tenant.slug())
        .param("name", tenant.name())
        .param("status", tenant.status())
        .param("at", OffsetDateTime.ofInstant(tenant.createdAt(), ZoneOffset.UTC))
        .update();
  }

  public Optional<Tenant> findBySlug(String slug) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM core_tenant WHERE slug = :slug")
        .param("slug", slug)
        .query(MAPPER)
        .optional();
  }

  public Optional<Tenant> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM core_tenant WHERE id = :id")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  public List<Tenant> page(int offset, int size) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM core_tenant ORDER BY created_at DESC, id LIMIT :size OFFSET :offset")
        .param("size", size)
        .param("offset", offset)
        .query(MAPPER)
        .list();
  }

  public long count() {
    return jdbc.sql("SELECT count(*) FROM core_tenant").query(Long.class).single();
  }

  public List<Tenant> findByIds(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT " + COLUMNS + " FROM core_tenant WHERE id IN (:ids)")
        .param("ids", ids)
        .query(MAPPER)
        .list();
  }
}
