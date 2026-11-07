package tech.toolkit.tenantcore.tenant;

import java.time.Instant;
import java.util.UUID;

/** A Tenant's subscription to a Module (MVP: only {@code platform}). */
public record ModuleSubscription(
    UUID id, UUID tenantId, String moduleCode, String status, Instant subscribedAt) {

  /** A subscription joined with its Tenant Slug, as shown in the module list. */
  public record View(String moduleCode, String tenantSlug, String status, Instant subscribedAt) {}
}
