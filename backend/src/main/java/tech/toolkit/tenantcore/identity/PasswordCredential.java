package tech.toolkit.tenantcore.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** 用户名密码类 Account 的凭证；密码只以不可逆哈希保存（FR-006）。 */
@Entity
@Table(name = "password_credential")
public class PasswordCredential {

  @Id
  @Column(name = "account_id")
  private UUID accountId;

  @TenantId
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private long tenantId;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "must_change_password", nullable = false)
  private boolean mustChangePassword;

  @Column(name = "password_changed_at")
  private Instant passwordChangedAt;

  protected PasswordCredential() {}

  public PasswordCredential(UUID accountId, String passwordHash, boolean mustChangePassword) {
    this.accountId = accountId;
    this.passwordHash = passwordHash;
    this.mustChangePassword = mustChangePassword;
    this.passwordChangedAt = Instant.now();
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public boolean isMustChangePassword() {
    return mustChangePassword;
  }

  public void change(String newHash, boolean mustChange) {
    this.passwordHash = newHash;
    this.mustChangePassword = mustChange;
    this.passwordChangedAt = Instant.now();
  }
}
