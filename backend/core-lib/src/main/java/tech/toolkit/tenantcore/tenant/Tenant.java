package tech.toolkit.tenantcore.tenant;

import java.time.Instant;
import java.util.UUID;

/** A customer organisation using the Platform, identified publicly by its Tenant Slug. */
public record Tenant(UUID id, String slug, String name, String status, Instant createdAt) {}
