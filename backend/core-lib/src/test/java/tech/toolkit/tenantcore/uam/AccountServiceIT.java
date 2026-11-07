package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.uam.AccountService.AccountCreation;

class AccountServiceIT extends PostgresTestBase {

  private final CoreComponents core = core();
  private final JdbcClient owner = JdbcClient.create(ownerDataSource());

  private User prepare(String slug) {
    core.tenants().create(slug, "Name " + slug, "test");
    core.loginMethods().enable(slug, "password", "test");
    return core.users().create(slug, "Zhang San", "test").user();
  }

  private static void assertCode(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(expected));
  }

  @Test
  void createsALowercasePasswordAccountThatMustChangeItsPassword() {
    User user = prepare("acme");
    AccountCreation created =
        core.accounts()
            .createPasswordAccount(
                "acme", user.tenantUserId(), "Admin1", PASSWORD, "cli:amos@host");
    assertThat(created.result()).isEqualTo(AccountCreation.Result.CREATED);
    assertThat(created.account().username()).isEqualTo("admin1");
    assertThat(created.account().userId()).isEqualTo(user.id());
    assertThat(
            owner
                .sql("SELECT must_change_password FROM uam_credential_password")
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(auditOutcomes("account.create")).containsExactly("SUCCESS");
  }

  @Test
  void neverStoresThePlainPassword() {
    User user = prepare("acme");
    core.accounts().createPasswordAccount("acme", user.tenantUserId(), "admin1", PASSWORD, "test");
    String hash =
        owner.sql("SELECT password_hash FROM uam_credential_password").query(String.class).single();
    assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
    assertThat(
            owner
                .sql("SELECT count(*) FROM audit_event WHERE target LIKE ?")
                .param("%" + PASSWORD + "%")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void repeatingTheCreateKeepsTheOriginalPassword() {
    User user = prepare("acme");
    core.accounts().createPasswordAccount("acme", user.tenantUserId(), "admin1", PASSWORD, "test");
    String before =
        owner.sql("SELECT password_hash FROM uam_credential_password").query(String.class).single();
    AccountCreation again =
        core.accounts()
            .createPasswordAccount(
                "acme", user.tenantUserId(), "admin1", "another password 123", "test");
    assertThat(again.result()).isEqualTo(AccountCreation.Result.EXISTS);
    assertThat(
            owner
                .sql("SELECT password_hash FROM uam_credential_password")
                .query(String.class)
                .single())
        .isEqualTo(before);
    assertThat(count("uam_account")).isEqualTo(1);
    assertThat(auditOutcomes("account.create")).containsExactly("SUCCESS", "NOOP");
  }

  @Test
  void rejectsUnknownTenantUnknownUserAndOtherTenantsUser() {
    User user = prepare("acme");
    prepare("globex");
    assertCode(
        () ->
            core.accounts()
                .createPasswordAccount("nope", user.tenantUserId(), "admin1", PASSWORD, "test"),
        ErrorCode.NOT_FOUND);
    assertCode(
        () ->
            core.accounts()
                .createPasswordAccount("acme", "tu_doesnotexist", "admin1", PASSWORD, "test"),
        ErrorCode.NOT_FOUND);
    assertCode(
        () ->
            core.accounts()
                .createPasswordAccount("globex", user.tenantUserId(), "admin1", PASSWORD, "test"),
        ErrorCode.NOT_FOUND);
    assertThat(count("uam_account")).isZero();
  }

  @Test
  void requiresPasswordLoginToBeEnabledForTheTenant() {
    core.tenants().create("acme", "Acme", "test");
    User user = core.users().create("acme", "Zhang San", "test").user();
    assertCode(
        () ->
            core.accounts()
                .createPasswordAccount("acme", user.tenantUserId(), "admin1", PASSWORD, "test"),
        ErrorCode.NOT_FOUND);
    assertThat(count("uam_account")).isZero();
  }

  @Test
  void usernameTakenByAnotherUserConflicts() {
    User first = prepare("acme");
    User second = core.users().create("acme", "Li Si", "test").user();
    core.accounts().createPasswordAccount("acme", first.tenantUserId(), "admin1", PASSWORD, "test");
    assertCode(
        () ->
            core.accounts()
                .createPasswordAccount("acme", second.tenantUserId(), "ADMIN1", PASSWORD, "test"),
        ErrorCode.CONFLICT);
    assertThat(count("uam_account")).isEqualTo(1);
  }

  @Test
  void sameUsernameInAnotherTenantIsFine() {
    User a = prepare("acme");
    User b = prepare("globex");
    core.accounts().createPasswordAccount("acme", a.tenantUserId(), "admin1", PASSWORD, "test");
    core.accounts().createPasswordAccount("globex", b.tenantUserId(), "admin1", PASSWORD, "test");
    assertThat(count("uam_account")).isEqualTo(2);
  }

  @Test
  void validatesUsernameAndPassword() {
    User user = prepare("acme");
    assertCode(
        () ->
            core.accounts()
                .createPasswordAccount("acme", user.tenantUserId(), "a b", PASSWORD, "test"),
        ErrorCode.VALIDATION);
    assertCode(
        () ->
            core.accounts()
                .createPasswordAccount("acme", user.tenantUserId(), "ab", PASSWORD, "test"),
        ErrorCode.VALIDATION);
    assertCode(
        () ->
            core.accounts()
                .createPasswordAccount("acme", user.tenantUserId(), "admin1", "short", "test"),
        ErrorCode.PASSWORD_POLICY);
    assertCode(
        () ->
            core.accounts()
                .createPasswordAccount(
                    "acme", user.tenantUserId(), "administrator1", "Administrator1", "test"),
        ErrorCode.PASSWORD_POLICY);
    assertThat(count("uam_account")).isZero();
    assertThat(count("uam_credential_password")).isZero();
  }
}
