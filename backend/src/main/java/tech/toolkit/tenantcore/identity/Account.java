package tech.toolkit.tenantcore.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** User 的一种登录凭证/登录方式（FR-001/002/003）。 */
@Entity
@Table(name = "account")
public class Account {

  @Id private UUID id = UUID.randomUUID();

  @TenantId
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private Long tenantId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private AccountType type;

  @Column(nullable = false, length = 64)
  private String identifier;

  @Column(name = "identifier_normalized", nullable = false, length = 64)
  private String identifierNormalized;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Status status = Status.ACTIVE;

  @Column(name = "failed_attempts", nullable = false)
  private int failedAttempts;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Version private Long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Account() {}

  public Account(UUID userId, AccountType type, String identifier) {
    this.userId = userId;
    this.type = type;
    this.identifier = identifier;
    this.identifierNormalized = normalize(identifier);
  }

  /** 登录标识规范化：NFKC + 小写，租户内大小写不敏感唯一（FR-014）。 */
  public static String normalize(String identifier) {
    return Normalizer.normalize(identifier, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  @PrePersist
  void onCreate() {
    createdAt = updatedAt = Instant.now();
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public boolean isLocked(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  public UUID getId() {
    return id;
  }

  public long getTenantId() {
    return tenantId == null ? 0 : tenantId;
  }

  public UUID getUserId() {
    return userId;
  }

  public AccountType getType() {
    return type;
  }

  public String getIdentifier() {
    return identifier;
  }

  public String getIdentifierNormalized() {
    return identifierNormalized;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public int getFailedAttempts() {
    return failedAttempts;
  }

  public void setFailedAttempts(int failedAttempts) {
    this.failedAttempts = failedAttempts;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  public void setLockedUntil(Instant lockedUntil) {
    this.lockedUntil = lockedUntil;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  public void setLastLoginAt(Instant lastLoginAt) {
    this.lastLoginAt = lastLoginAt;
  }

  public Long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
