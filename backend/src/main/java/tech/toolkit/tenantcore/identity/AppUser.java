package tech.toolkit.tenantcore.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.TenantId;

/** 人的身份主体。每个 User 必然有用户名与密码（FR-001），它们绑定在 User 自身；其他登录方式（手机号、微信、OAuth2.0）日后以 Account 各自分表关联。 */
@Entity
@Table(name = "app_user")
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @TenantId
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private Long tenantId;

  @Column(name = "display_name", nullable = false, length = 50)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Status status = Status.ACTIVE;

  @Column(nullable = false, length = 64)
  private String username;

  @Column(name = "username_normalized", nullable = false, length = 64)
  private String usernameNormalized;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "must_change_password", nullable = false)
  private boolean mustChangePassword;

  @Column(name = "password_changed_at")
  private Instant passwordChangedAt;

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

  protected AppUser() {}

  public AppUser(
      String displayName,
      Role role,
      String username,
      String passwordHash,
      boolean mustChangePassword) {
    this.displayName = displayName;
    this.role = role;
    this.username = username;
    this.usernameNormalized = IdentityRules.normalizeUsername(username);
    this.passwordHash = passwordHash;
    this.mustChangePassword = mustChangePassword;
    this.passwordChangedAt = Instant.now();
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

  /** 写入新密码哈希；{@code mustChange} 为 true 表示临时密码（须在下次登录后修改）。 */
  public void changePassword(String newHash, boolean mustChange) {
    this.passwordHash = newHash;
    this.mustChangePassword = mustChange;
    this.passwordChangedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public long getTenantId() {
    return tenantId == null ? 0 : tenantId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public Role getRole() {
    return role;
  }

  public void setRole(Role role) {
    this.role = role;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public String getUsername() {
    return username;
  }

  public String getUsernameNormalized() {
    return usernameNormalized;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public boolean isMustChangePassword() {
    return mustChangePassword;
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
