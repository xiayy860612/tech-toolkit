package tech.toolkit.tenantcore.audit;

import java.util.Map;

/** 一条待写入的审计事件；{@code detail} 在写入前会被脱敏（永不含密码、令牌、JWT）。 */
public record AuditEvent(
    AuditAction action,
    Long actorUserId,
    TargetType targetType,
    Long targetId,
    boolean success,
    String reason,
    String ip,
    Map<String, Object> detail) {

  public enum TargetType {
    USER
  }

  public static AuditEvent success(
      AuditAction action,
      Long actorUserId,
      TargetType targetType,
      Long targetId,
      String ip,
      Map<String, Object> detail) {
    return new AuditEvent(action, actorUserId, targetType, targetId, true, null, ip, detail);
  }

  public static AuditEvent failure(
      AuditAction action,
      Long actorUserId,
      TargetType targetType,
      Long targetId,
      String reason,
      String ip,
      Map<String, Object> detail) {
    return new AuditEvent(action, actorUserId, targetType, targetId, false, reason, ip, detail);
  }
}
