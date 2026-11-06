package tech.toolkit.tenantcore.uam;

import java.time.Instant;
import java.util.UUID;

/**
 * The stored side of an Opaque Token (the session id): who it belongs to and when it is valid. The
 * token value itself is never stored, only its SHA-256 hash; {@code sid} is a public identifier of
 * the Session and goes into Internal JWTs.
 */
public record OpaqueToken(
    String sid,
    UUID accountId,
    UUID userId,
    UUID tenantId,
    Instant createdAt,
    Instant lastSeenAt,
    Instant expiresAt,
    Instant revokedAt) {}
