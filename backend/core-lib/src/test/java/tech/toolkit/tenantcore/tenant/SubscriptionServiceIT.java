package tech.toolkit.tenantcore.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.tenant.SubscriptionService.SubscriptionResult;

class SubscriptionServiceIT extends PostgresTestBase {

  private final CoreComponents core = core();

  @Test
  void subscribesATenantToPlatformAndAuditsIt() {
    Tenant acme = core.tenants().create("acme", "Acme", "test").tenant();
    assertThat(core.subscriptions().add("acme", "platform", "cli:amos@host"))
        .isEqualTo(SubscriptionResult.SUBSCRIBED);
    assertThat(core.tenants().hasSubscription(acme.id(), "platform")).isTrue();
    assertThat(auditOutcomes("subscription.add")).containsExactly("SUCCESS");
  }

  @Test
  void subscribingTwiceIsANoop() {
    core.tenants().create("acme", "Acme", "test");
    core.subscriptions().add("acme", "platform", "test");
    assertThat(core.subscriptions().add("acme", "platform", "test"))
        .isEqualTo(SubscriptionResult.ALREADY_SUBSCRIBED);
    assertThat(count("core_module_subscription")).isEqualTo(1);
    assertThat(auditOutcomes("subscription.add")).containsExactly("SUCCESS", "NOOP");
  }

  @Test
  void rejectsUnknownModuleAndUnknownTenant() {
    core.tenants().create("acme", "Acme", "test");
    assertThatThrownBy(() -> core.subscriptions().add("acme", "billing", "test"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    assertThatThrownBy(() -> core.subscriptions().add("nope", "platform", "test"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    assertThat(count("core_module_subscription")).isZero();
  }
}
