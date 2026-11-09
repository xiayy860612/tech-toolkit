package tech.toolkit.tenantcore.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.Page;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.uam.UserQueryService.UserView;

/** The three read-only lists of the console: Tenants, Module Subscriptions and Users. */
class ListQueriesIT extends PostgresTestBase {

  private final MutableClock clock = new MutableClock();
  private final CoreComponents core = core(clock);

  private void tick() {
    clock.advance(Duration.ofSeconds(1));
  }

  @Test
  void tenantsArePagedNewestFirstWithATotal() {
    for (int i = 1; i <= 25; i++) {
      core.tenants().create(String.format("tenant-%02d", i), "Tenant " + i, "test");
      tick();
    }
    Page<Tenant> first = core.tenantQueries().tenants(0, 20);
    assertThat(first.total()).isEqualTo(25);
    assertThat(first.items()).hasSize(20);
    assertThat(first.items().get(0).slug()).isEqualTo("tenant-25");
    Page<Tenant> second = core.tenantQueries().tenants(1, 20);
    assertThat(second.items()).hasSize(5);
    assertThat(second.items().get(4).slug()).isEqualTo("tenant-01");
    assertThat(second.page()).isEqualTo(1);
  }

  @Test
  void pageRequestsAreClamped() {
    core.tenants().create("acme", "Acme", "test");
    assertThat(core.tenantQueries().tenants(-3, 0).page()).isZero();
    assertThat(core.tenantQueries().tenants(0, 0).size()).isEqualTo(Page.DEFAULT_SIZE);
    assertThat(core.tenantQueries().tenants(0, 100000).size()).isEqualTo(Page.MAX_SIZE);
  }

  @Test
  void subscriptionsShowTheTenantSlug() {
    core.tenants().create("acme", "Acme", "test");
    core.tenants().create("globex", "Globex", "test");
    core.subscriptions().add("acme", "platform", "test");
    tick();
    core.subscriptions().add("globex", "platform", "test");

    Page<ModuleSubscription.View> page = core.tenantQueries().subscriptions(0, 20);

    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items())
        .extracting(ModuleSubscription.View::tenantSlug)
        .containsExactly("globex", "acme");
    assertThat(page.items().get(0).moduleCode()).isEqualTo("platform");
    assertThat(page.items().get(0).status()).isEqualTo("SUBSCRIBED");
    assertThat(page.items().get(0).subscribedAt()).isNotNull();
  }

  @Test
  void usersShowTheirAccountUsernamesAndTenantSlug() {
    bootstrap(core, "acme", "admin1");
    tick();
    core.tenants().create("globex", "Globex", "test");
    core.users().create("globex", "No Account Yet", "test");

    Page<UserView> page = core.userQueries().users(0, 20);

    assertThat(page.total()).isEqualTo(2);
    UserView withoutAccount = page.items().get(0); // newest first
    assertThat(withoutAccount.displayName()).isEqualTo("No Account Yet");
    assertThat(withoutAccount.accountUsernames()).isEmpty();
    assertThat(withoutAccount.tenantSlug()).isEqualTo("globex");
    UserView withAccount = page.items().get(1);
    assertThat(withAccount.displayName()).isEqualTo("User admin1");
    assertThat(withAccount.accountUsernames()).containsExactly("admin1");
    assertThat(withAccount.tenantSlug()).isEqualTo("acme");
    assertThat(withAccount.tenantUserId()).startsWith("tu_");
    assertThat(withAccount.status()).isEqualTo("ACTIVE");
  }

  @Test
  void userViewsNeverContainCredentialData() {
    bootstrap(core, "acme", "admin1");
    String rendered = core.userQueries().users(0, 20).toString();
    assertThat(rendered).doesNotContain("argon2").doesNotContain(PASSWORD).doesNotContain("hash");
  }

  @Test
  void slugsAreResolvedByTenantIdWithoutAJoin() {
    Tenant acme = core.tenants().create("acme", "Acme", "test").tenant();
    assertThat(core.tenantQueries().slugsByIds(java.util.List.of(acme.id())))
        .containsEntry(acme.id(), "acme");
    assertThat(core.tenantQueries().slugsByIds(java.util.List.of())).isEmpty();
  }
}
