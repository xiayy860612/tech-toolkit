package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.uam.LoginMethodService.EnableResult;

class LoginMethodServiceIT extends PostgresTestBase {

  private final CoreComponents core = core();

  @Test
  void enablesPasswordLoginAndAuditsIt() {
    core.tenants().create("acme", "Acme", "test");
    assertThat(core.loginMethods().enable("acme", "password", "cli:amos@host"))
        .isEqualTo(EnableResult.ENABLED);
    assertThat(count("uam_tenant_login_method")).isEqualTo(1);
    assertThat(auditOutcomes("login-method.enable")).containsExactly("SUCCESS");
  }

  @Test
  void enablingTwiceIsANoop() {
    core.tenants().create("acme", "Acme", "test");
    core.loginMethods().enable("acme", "password", "test");
    assertThat(core.loginMethods().enable("acme", "PASSWORD", "test"))
        .isEqualTo(EnableResult.ALREADY_ENABLED);
    assertThat(count("uam_tenant_login_method")).isEqualTo(1);
    assertThat(auditOutcomes("login-method.enable")).containsExactly("SUCCESS", "NOOP");
  }

  @Test
  void unknownTenantIsNotFound() {
    assertThatThrownBy(() -> core.loginMethods().enable("nope", "password", "test"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void onlyPasswordIsSupported() {
    core.tenants().create("acme", "Acme", "test");
    assertThatThrownBy(() -> core.loginMethods().enable("acme", "oidc", "test"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION));
  }
}
