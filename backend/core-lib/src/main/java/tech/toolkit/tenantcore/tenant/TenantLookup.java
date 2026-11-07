package tech.toolkit.tenantcore.tenant;

import java.util.Optional;
import java.util.UUID;

/** What other packages (uam) may ask the tenant package; they never read core_* tables directly. */
public interface TenantLookup {

  Optional<Tenant> findBySlug(String slug);

  Optional<Tenant> findById(UUID id);

  boolean hasSubscription(UUID tenantId, String moduleCode);
}
