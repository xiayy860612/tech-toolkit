package tech.toolkit.tenantcore.audit;

import java.util.UUID;

/**
 * One audit record. {@code actor} is {@code cli:<os-user>@<host>}, a {@code tu_...} User ID or
 * {@code anonymous}. {@code target} describes the object acted on and must never contain secrets.
 */
public record AuditEvent(
    String actor, String action, UUID tenantId, String target, Outcome outcome, String traceId) {

  public enum Outcome {
    SUCCESS,
    FAILURE,
    NOOP
  }
}
