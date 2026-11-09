package tech.toolkit.tenantcore.uam;

import java.util.UUID;

/**
 * Who is calling, however they proved it. {@code sid} is the public Session identifier shared by
 * the Opaque Token and every Internal JWT exchanged from it.
 */
public record AuthenticatedPrincipal(
    Source source, String tenantUserId, UUID tenantId, String sid) {

  /** How the caller authenticated: an Opaque Token (session id) or an Internal JWT. */
  public enum Source {
    OPAQUE,
    JWT
  }
}
