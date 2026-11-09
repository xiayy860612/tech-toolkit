package tech.toolkit.tenantcore.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.tenant.TenantDetailService.TenantDetail;

class TenantDetailIT extends PostgresTestBase {

  private final CoreComponents core = core();

  @Test
  void showsLoginMethodsUsersWithTheirAccountsAndModules() {
    bootstrap(core, "acme", "admin1");
    core.users().create("acme", "Li Si", "test"); // a User without an Account

    TenantDetail detail = core.tenantDetails().show("acme");

    assertThat(detail.tenant().slug()).isEqualTo("acme");
    assertThat(detail.loginMethods()).containsExactly("PASSWORD");
    assertThat(detail.modules()).containsExactly("platform");
    assertThat(detail.users()).hasSize(2);
    assertThat(detail.users())
        .anySatisfy(
            u -> {
              assertThat(u.displayName()).isEqualTo("User admin1");
              assertThat(u.accountUsernames()).containsExactly("admin1");
              assertThat(u.tenantUserId()).startsWith("tu_");
            })
        .anySatisfy(
            u -> {
              assertThat(u.displayName()).isEqualTo("Li Si");
              assertThat(u.accountUsernames()).isEmpty();
            });
  }

  @Test
  void neverExposesCredentialData() {
    bootstrap(core, "acme", "admin1");
    String rendered = core.tenantDetails().show("acme").toString();
    assertThat(rendered).doesNotContain("argon2").doesNotContain(PASSWORD);
  }

  @Test
  void unknownTenantIsNotFound() {
    assertThatThrownBy(() -> core.tenantDetails().show("nope"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
  }
}
