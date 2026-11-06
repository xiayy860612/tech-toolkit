package tech.toolkit.tenantcore.tenant;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class SubscriptionRepository {

  private final JdbcClient jdbc;

  public SubscriptionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(ModuleSubscription subscription) {
    jdbc.sql(
            "INSERT INTO core_module_subscription(id, tenant_id, module_code, status, subscribed_at)"
                + " VALUES (:id, :tenant, :module, :status, :at)")
        .param("id", subscription.id())
        .param("tenant", subscription.tenantId())
        .param("module", subscription.moduleCode())
        .param("status", subscription.status())
        .param("at", OffsetDateTime.ofInstant(subscription.subscribedAt(), ZoneOffset.UTC))
        .update();
  }

  public boolean exists(UUID tenantId, String moduleCode) {
    return jdbc.sql(
                "SELECT count(*) FROM core_module_subscription"
                    + " WHERE tenant_id = :tenant AND module_code = :module")
            .param("tenant", tenantId)
            .param("module", moduleCode)
            .query(Long.class)
            .single()
        > 0;
  }

  public List<String> modulesOf(UUID tenantId) {
    return jdbc.sql(
            "SELECT module_code FROM core_module_subscription WHERE tenant_id = :tenant"
                + " ORDER BY module_code")
        .param("tenant", tenantId)
        .query(String.class)
        .list();
  }

  public List<ModuleSubscription.View> page(int offset, int size) {
    return jdbc.sql(
            "SELECT s.module_code, t.slug, s.status, s.subscribed_at"
                + " FROM core_module_subscription s JOIN core_tenant t ON t.id = s.tenant_id"
                + " ORDER BY s.subscribed_at DESC, s.id LIMIT :size OFFSET :offset")
        .param("size", size)
        .param("offset", offset)
        .query(
            (rs, i) ->
                new ModuleSubscription.View(
                    rs.getString("module_code"),
                    rs.getString("slug"),
                    rs.getString("status"),
                    rs.getObject("subscribed_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  public long count() {
    return jdbc.sql("SELECT count(*) FROM core_module_subscription").query(Long.class).single();
  }
}
