package tech.toolkit.tenantcore.uam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import tech.toolkit.tenantcore.common.IdGenerator;

/**
 * Issues, validates and revokes Opaque Tokens (the session id carried in the {@code X-Session-Id}
 * request header). A token is valid while it is not revoked, was used within the idle timeout and
 * is younger than the absolute lifetime; every successful use slides the idle window.
 */
public class OpaqueTokenService {

  /** The plain token value (shown once) with its stored record. */
  public record IssuedToken(String value, OpaqueToken token) {}

  private final OpaqueTokenRepository repository;
  private final IdGenerator ids;
  private final Clock clock;

  public OpaqueTokenService(OpaqueTokenRepository repository, IdGenerator ids, Clock clock) {
    this.repository = repository;
    this.ids = ids;
    this.clock = clock;
  }

  public IssuedToken issue(UUID accountId, UUID userId, UUID tenantId) {
    String value = ids.opaqueTokenValue();
    Instant now = clock.instant();
    OpaqueToken token =
        new OpaqueToken(
            ids.sid(),
            accountId,
            userId,
            tenantId,
            now,
            now,
            now.plus(UamSettings.ABSOLUTE_TIMEOUT),
            null);
    repository.insert(hash(value), token);
    return new IssuedToken(value, token);
  }

  /** Returns the token record if the value is currently valid, and slides the idle window. */
  public Optional<OpaqueToken> authenticate(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    byte[] hash = hash(value);
    Instant now = clock.instant();
    Optional<OpaqueToken> found = repository.findByHash(hash);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    OpaqueToken token = found.get();
    boolean valid =
        token.revokedAt() == null
            && now.isBefore(token.expiresAt())
            && now.isBefore(token.lastSeenAt().plus(UamSettings.IDLE_TIMEOUT));
    if (!valid) {
      return Optional.empty();
    }
    repository.touch(hash, now);
    return Optional.of(token);
  }

  /** Revokes the token; returns its record when it existed (even if already expired or revoked). */
  public Optional<OpaqueToken> revoke(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    byte[] hash = hash(value);
    Optional<OpaqueToken> found = repository.findByHash(hash);
    found.ifPresent(token -> repository.revoke(hash, clock.instant()));
    return found;
  }

  /** The record behind a public Session identifier, valid or not (used for display only). */
  public Optional<OpaqueToken> findBySid(String sid) {
    return repository.findBySid(sid);
  }

  static byte[] hash(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
