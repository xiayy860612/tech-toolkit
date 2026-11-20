package tech.toolkit.tenantcore.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/** 登录会话；令牌明文不落库，只存 SHA-256。{@code id} 是公开标识（不是令牌）。 */
@Entity
@Table(name = "session")
public class Session {

  @Id private UUID id = UUID.randomUUID();

  @TenantId
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private long tenantId;

  @Column(name = "token_hash", nullable = false, updatable = false)
  private byte[] tokenHash;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "account_id", nullable = false, updatable = false)
  private UUID accountId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  @Column(name = "absolute_expires_at", nullable = false, updatable = false)
  private Instant absoluteExpiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "revoke_reason", length = 30)
  private RevokeReason revokeReason;

  @JdbcTypeCode(SqlTypes.INET)
  private InetAddress ip;

  @Column(name = "user_agent", length = 512)
  private String userAgent;

  @Column(nullable = false, length = 100)
  private String device = "";

  protected Session() {}

  Session(
      byte[] tokenHash,
      UUID userId,
      UUID accountId,
      Instant now,
      Instant absoluteExpiresAt,
      InetAddress ip,
      String userAgent,
      String device) {
    this.tokenHash = tokenHash;
    this.userId = userId;
    this.accountId = accountId;
    this.createdAt = now;
    this.lastSeenAt = now;
    this.absoluteExpiresAt = absoluteExpiresAt;
    this.ip = ip;
    this.userAgent = userAgent;
    this.device = device;
  }

  public UUID getId() {
    return id;
  }

  public long getTenantId() {
    return tenantId;
  }

  public byte[] getTokenHash() {
    return tokenHash;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public Instant getAbsoluteExpiresAt() {
    return absoluteExpiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public RevokeReason getRevokeReason() {
    return revokeReason;
  }

  public InetAddress getIp() {
    return ip;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public String getDevice() {
    return device;
  }
}
