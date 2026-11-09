package tech.toolkit.tenantcore.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.tenant.TenantService.TenantCreation;

class TenantServiceIT extends PostgresTestBase {

  private final TenantService tenants = core().tenants();

  @Test
  void createsAnActiveTenantAndAuditsIt() {
    TenantCreation created = tenants.create("acme", "Acme Corp", "cli:amos@host");
    assertThat(created.result()).isEqualTo(TenantCreation.Result.CREATED);
    assertThat(created.tenant().slug()).isEqualTo("acme");
    assertThat(created.tenant().name()).isEqualTo("Acme Corp");
    assertThat(created.tenant().status()).isEqualTo("ACTIVE");
    assertThat(auditOutcomes("tenant.create")).containsExactly("SUCCESS");
  }

  @Test
  void repeatingTheSameCreateIsANoop() {
    tenants.create("acme", "Acme Corp", "cli:amos@host");
    TenantCreation again = tenants.create("acme", "Acme Corp", "cli:amos@host");
    assertThat(again.result()).isEqualTo(TenantCreation.Result.EXISTS);
    assertThat(count("core_tenant")).isEqualTo(1);
    assertThat(auditOutcomes("tenant.create")).containsExactly("SUCCESS", "NOOP");
  }

  @Test
  void sameSlugWithAnotherNameConflicts() {
    tenants.create("acme", "Acme Corp", "cli:amos@host");
    assertThatThrownBy(() -> tenants.create("acme", "Other Name", "cli:amos@host"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    assertThat(count("core_tenant")).isEqualTo(1);
  }

  @Test
  void rejectsInvalidSlugsAndNames() {
    for (String slug : List.of("Ab", "ab", "1abc", "acme_corp", "a".repeat(33), "ACME", "", " ")) {
      assertThatThrownBy(() -> tenants.create(slug, "Acme", "cli:amos@host"))
          .as("slug '%s'", slug)
          .isInstanceOfSatisfying(
              DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION));
    }
    assertThatThrownBy(() -> tenants.create("acme", " ", "cli:amos@host"))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> tenants.create("acme", "x".repeat(101), "cli:amos@host"))
        .isInstanceOf(DomainException.class);
    assertThat(count("core_tenant")).isZero();
  }

  @Test
  void looksUpTenantsBySlugAndId() {
    Tenant acme = tenants.create("acme", "Acme Corp", "cli:amos@host").tenant();
    assertThat(tenants.findBySlug("acme")).contains(acme);
    assertThat(tenants.findById(acme.id())).contains(acme);
    assertThat(tenants.findBySlug("nope")).isEmpty();
    assertThat(tenants.hasSubscription(acme.id(), "platform")).isFalse();
  }
}
