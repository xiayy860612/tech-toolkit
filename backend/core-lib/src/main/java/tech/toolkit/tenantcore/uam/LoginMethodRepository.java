package tech.toolkit.tenantcore.uam;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class LoginMethodRepository {

  private final JdbcClient jdbc;

  public LoginMethodRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public boolean isEnabled(UUID tenantId, String methodType) {
    return jdbc.sql(
                "SELECT count(*) FROM uam_tenant_login_method"
                    + " WHERE tenant_id = :tenant AND method_type = :type")
            .param("tenant", tenantId)
            .param("type", methodType)
            .query(Long.class)
            .single()
        > 0;
  }

  public void insert(UUID tenantId, String methodType, Instant enabledAt) {
    jdbc.sql(
            "INSERT INTO uam_tenant_login_method(tenant_id, method_type, enabled_at)"
                + " VALUES (:tenant, :type, :at)")
        .param("tenant", tenantId)
        .param("type", methodType)
        .param("at", OffsetDateTime.ofInstant(enabledAt, ZoneOffset.UTC))
        .update();
  }

  public List<String> methodsOf(UUID tenantId) {
    return jdbc.sql(
            "SELECT method_type FROM uam_tenant_login_method WHERE tenant_id = :tenant"
                + " ORDER BY method_type")
        .param("tenant", tenantId)
        .query(String.class)
        .list();
  }
}
