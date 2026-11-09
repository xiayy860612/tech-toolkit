package tech.toolkit.tenantcore.tenant;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import tech.toolkit.tenantcore.common.Page;

/** Read-only lists for the console: Tenants and Module Subscriptions. */
public class TenantQueryService {

  private final TenantRepository tenants;
  private final SubscriptionRepository subscriptions;

  public TenantQueryService(TenantRepository tenants, SubscriptionRepository subscriptions) {
    this.tenants = tenants;
    this.subscriptions = subscriptions;
  }

  public Page<Tenant> tenants(int page, int size) {
    int p = Page.normalizePage(page);
    int s = Page.normalizeSize(size);
    return new Page<>(tenants.page(p * s, s), p, s, tenants.count());
  }

  public Page<ModuleSubscription.View> subscriptions(int page, int size) {
    int p = Page.normalizePage(page);
    int s = Page.normalizeSize(size);
    return new Page<>(subscriptions.page(p * s, s), p, s, subscriptions.count());
  }

  /** Tenant Slugs by Tenant id; the uam package uses this instead of joining core_tenant. */
  public Map<UUID, String> slugsByIds(Collection<UUID> ids) {
    Map<UUID, String> slugs = new HashMap<>();
    tenants.findByIds(ids).forEach(t -> slugs.put(t.id(), t.slug()));
    return slugs;
  }
}
