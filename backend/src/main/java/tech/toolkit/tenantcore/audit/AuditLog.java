package tech.toolkit.tenantcore.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @TenantId
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private Long tenantId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt = Instant.now();

  @Column(name = "actor_user_id")
  private Long actorUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private AuditAction action;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", length = 10)
  private AuditEvent.TargetType targetType;

  @Column(name = "target_id")
  private Long targetId;

  @Column(nullable = false, length = 10)
  private String result;

  @Column(length = 100)
  private String reason;

  @JdbcTypeCode(SqlTypes.INET)
  private InetAddress ip;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> detail;

  protected AuditLog() {}

  AuditLog(AuditEvent e, InetAddress ip, Map<String, Object> detail) {
    this.actorUserId = e.actorUserId();
    this.action = e.action();
    this.targetType = e.targetType();
    this.targetId = e.targetId();
    this.result = e.success() ? "SUCCESS" : "FAILURE";
    this.reason = e.reason();
    this.ip = ip;
    this.detail = detail;
  }

  public Long getId() {
    return id;
  }

  public long getTenantId() {
    return tenantId == null ? 0 : tenantId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public Long getActorUserId() {
    return actorUserId;
  }

  public AuditAction getAction() {
    return action;
  }

  public AuditEvent.TargetType getTargetType() {
    return targetType;
  }

  public Long getTargetId() {
    return targetId;
  }

  public String getResult() {
    return result;
  }

  public String getReason() {
    return reason;
  }

  public InetAddress getIp() {
    return ip;
  }

  public Map<String, Object> getDetail() {
    return detail;
  }
}
