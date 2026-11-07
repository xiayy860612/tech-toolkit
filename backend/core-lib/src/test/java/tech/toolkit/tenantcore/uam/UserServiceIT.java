package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.uam.UserService.UserCreation;

class UserServiceIT extends PostgresTestBase {

  private final CoreComponents core = core();

  @Test
  void createsAUserWithAPublicUserId() {
    core.tenants().create("acme", "Acme", "test");
    UserCreation created = core.users().create("acme", "Zhang San", "cli:amos@host");
    assertThat(created.result()).isEqualTo(UserCreation.Result.CREATED);
    assertThat(created.user().tenantUserId()).startsWith("tu_");
    assertThat(created.user().displayName()).isEqualTo("Zhang San");
    assertThat(created.user().status()).isEqualTo("ACTIVE");
    assertThat(auditOutcomes("user.create")).containsExactly("SUCCESS");
  }

  @Test
  void sameDisplayNameInTheSameTenantIsTheSameUser() {
    core.tenants().create("acme", "Acme", "test");
    User first = core.users().create("acme", "Zhang San", "test").user();
    UserCreation again = core.users().create("acme", "Zhang San", "test");
    assertThat(again.result()).isEqualTo(UserCreation.Result.EXISTS);
    assertThat(again.user().tenantUserId()).isEqualTo(first.tenantUserId());
    assertThat(count("uam_user")).isEqualTo(1);
  }

  @Test
  void sameDisplayNameInAnotherTenantIsAnotherUser() {
    core.tenants().create("acme", "Acme", "test");
    core.tenants().create("globex", "Globex", "test");
    User a = core.users().create("acme", "Zhang San", "test").user();
    User b = core.users().create("globex", "Zhang San", "test").user();
    assertThat(a.tenantUserId()).isNotEqualTo(b.tenantUserId());
    assertThat(count("uam_user")).isEqualTo(2);
  }

  @Test
  void rejectsMissingTenantAndBadDisplayNames() {
    assertThatThrownBy(() -> core.users().create("nope", "Zhang San", "test"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    core.tenants().create("acme", "Acme", "test");
    assertThatThrownBy(() -> core.users().create("acme", "  ", "test"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION));
    assertThatThrownBy(() -> core.users().create("acme", "x".repeat(101), "test"))
        .isInstanceOf(DomainException.class);
    assertThat(count("uam_user")).isZero();
  }
}
