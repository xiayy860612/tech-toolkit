package tech.toolkit.auth.audit;

import java.util.Map;
import java.util.Objects;

/**
 * One security event. {@code detail} must never carry passwords or token values; {@link
 * AuditService} rejects anything that looks like one.
 */
public record AuditEvent(
    AuditType type,
    String actor,
    Long subjectUserId,
    String clientId,
    String sourceIp,
    boolean success,
    Map<String, Object> detail) {

  public AuditEvent {
    Objects.requireNonNull(type, "type");
    detail = detail == null ? Map.of() : Map.copyOf(detail);
  }

  public static AuditEvent of(AuditType type, boolean success) {
    return new AuditEvent(type, null, null, null, null, success, Map.of());
  }

  public AuditEvent actor(String value) {
    return new AuditEvent(type, value, subjectUserId, clientId, sourceIp, success, detail);
  }

  public AuditEvent subjectUserId(Long value) {
    return new AuditEvent(type, actor, value, clientId, sourceIp, success, detail);
  }

  public AuditEvent clientId(String value) {
    return new AuditEvent(type, actor, subjectUserId, value, sourceIp, success, detail);
  }

  public AuditEvent sourceIp(String value) {
    return new AuditEvent(type, actor, subjectUserId, clientId, value, success, detail);
  }

  public AuditEvent detail(Map<String, Object> value) {
    return new AuditEvent(type, actor, subjectUserId, clientId, sourceIp, success, value);
  }
}
