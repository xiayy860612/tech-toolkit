package tech.toolkit.tenantcore.uam;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries the login credentials of one User. An Account belongs to exactly one User; a User may
 * have several Accounts (MVP: one {@code PASSWORD} Account). {@code username} is unique per Tenant.
 */
public record Account(
    UUID id, UUID userId, UUID tenantId, String type, String username, Instant createdAt) {}
