package tech.toolkit.tenantcore.audit;

import java.util.Map;
import java.util.UUID;

/** 一条待写入的审计事件；{@code detail} 在写入前会被脱敏（永不含密码、令牌、JWT）。 */
public record AuditEvent(
    AuditAction action,
    UUID actorUserId,
    UUID actorAccountId,
    TargetType targetType,
    UUID targetId,
    boolean success,
    String reason,
    String ip,
    Map<String, Object> detail) {

  public enum TargetType {
    USER,
    ACCOUNT
  }

  public static AuditEvent success(
      AuditAction action,
      UUID actorUserId,
      UUID actorAccountId,
      TargetType targetType,
      UUID targetId,
      String ip,
      Map<String, Object> detail) {
    return new AuditEvent(
        action, actorUserId, actorAccountId, targetType, targetId, true, null, ip, detail);
  }

  public static AuditEvent failure(
      AuditAction action,
      UUID actorUserId,
      UUID actorAccountId,
      TargetType targetType,
      UUID targetId,
      String reason,
      String ip,
      Map<String, Object> detail) {
    return new AuditEvent(
        action, actorUserId, actorAccountId, targetType, targetId, false, reason, ip, detail);
  }
}
