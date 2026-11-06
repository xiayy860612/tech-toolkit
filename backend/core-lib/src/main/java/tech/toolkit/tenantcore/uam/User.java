package tech.toolkit.tenantcore.uam;

import java.time.Instant;
import java.util.UUID;

/**
 * A person inside a Tenant. {@code tenantUserId} ({@code tu_...}) is the public User ID; {@code id}
 * is the internal key and is never exposed.
 */
public record User(
    UUID id,
    String tenantUserId,
    UUID tenantId,
    String displayName,
    String status,
    Instant createdAt) {}
