package tech.toolkit.tenantcore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.toolkit.tenantcore.auth.SessionService;
import tech.toolkit.tenantcore.platform.Actor;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.PageResult;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestSessions;

class UserServiceTest extends AbstractPostgresIT {

  private static final AtomicLong TENANTS = new AtomicLong(100);
  private static final String PW = "Str0ng-Passw0rd";

  @Autowired UserService service;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;
  @Autowired SessionService sessionService;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
  private long tenant;
  private TestAccounts.Created admin;
  private Actor actor;

  @BeforeEach
  void tenantWithOneAdmin() {
    tenant = TENANTS.incrementAndGet();
    admin = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    actor = new Actor(admin.user().getId(), admin.account().getId(), "203.0.113.5");
  }

  private <T> T inTenant(Supplier<T> action) {
    return TenantContext.callAs(tenant, action);
  }

  private UserService.Created create(String username) {
    return inTenant(
        () ->
            service.create(
                actor, new UserService.NewUser("Alice", null, null, Role.MEMBER, username, null)));
  }

  private int auditCount(String action) {
    return owner.queryForObject(
        "select count(*) from audit_log where tenant_id = ? and action = ?",
        Integer.class,
        tenant,
        action);
  }

  private static void assertCode(Throwable t, ErrorCode code) {
    assertThat(t)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  @Test
  void createsAUserWithAUsernamePasswordAccountThatMustChangeItsPassword() {
    UserService.Created created = create("alice.w");

    assertThat(created.user().user().getRole()).isEqualTo(Role.MEMBER);
    assertThat(created.user().accountCount()).isEqualTo(1);
    assertThat(created.user().primaryUsername()).isEqualTo("alice.w");
    assertThat(created.account().account().getType()).isEqualTo(AccountType.USERNAME_PASSWORD);
    assertThat(created.account().mustChangePassword()).isTrue();
    assertThat(created.temporaryPassword()).isNotBlank();
    assertThat(auditCount("USER_CREATED")).isEqualTo(1);
    assertThat(
            owner.queryForObject(
                "select detail::text from audit_log where action='USER_CREATED' and target_id = ?",
                String.class,
                created.user().user().getId()))
        .doesNotContain(created.temporaryPassword());
  }

  @Test
  void anAdministratorSuppliedTemporaryPasswordIsNotReturned() {
    var created =
        inTenant(
            () ->
                service.create(
                    actor,
                    new UserService.NewUser(
                        "Bob", null, null, Role.MEMBER, "bob.b", "Giv3n-Passw0rd!")));
    assertThat(created.temporaryPassword()).isNull();
    assertThat(created.account().mustChangePassword()).isTrue();
  }

  @Test
  void aWeakSuppliedTemporaryPasswordIsRejected() {
    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        service.create(
                            actor,
                            new UserService.NewUser(
                                "Bob", null, null, Role.MEMBER, "bob.c", "weak"))))
        .satisfies(e -> assertCode(e, ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void validatesDisplayNamePhoneEmailAndUsername() {
    for (UserService.NewUser bad :
        List.of(
            new UserService.NewUser("", null, null, Role.MEMBER, "okname", null),
            new UserService.NewUser("x".repeat(51), null, null, Role.MEMBER, "okname", null),
            new UserService.NewUser("N", "12345", null, Role.MEMBER, "okname", null),
            new UserService.NewUser("N", null, "not-an-email", Role.MEMBER, "okname", null),
            new UserService.NewUser("N", null, null, Role.MEMBER, "ab", null),
            new UserService.NewUser("N", null, null, Role.MEMBER, "-leading", null),
            new UserService.NewUser("N", null, null, Role.MEMBER, "has space", null))) {
      assertThatThrownBy(() -> inTenant(() -> service.create(actor, bad)))
          .satisfies(e -> assertCode(e, ErrorCode.VALIDATION_FAILED));
    }
    var ok =
        inTenant(
            () ->
                service.create(
                    actor,
                    new UserService.NewUser(
                        "N", "13812345678", "n@x.io", Role.MEMBER, "okname", null)));
    assertThat(ok.user().user().getPhone()).isEqualTo("13812345678");
  }

  @Test
  void usernamesAreUniqueWithinTheTenantIgnoringCase() {
    create("Carol.C");
    assertThatThrownBy(() -> create("carol.c"))
        .satisfies(e -> assertCode(e, ErrorCode.USERNAME_TAKEN));
  }

  @Test
  void updatesWithOptimisticLocking() {
    var created = create("dave.d");
    UUID id = created.user().user().getId();
    long version = created.user().user().getVersion();

    var updated =
        inTenant(
            () ->
                service.update(
                    actor,
                    id,
                    new UserService.Changes("Dave D", null, null, Role.MEMBER, version)));
    assertThat(updated.user().getDisplayName()).isEqualTo("Dave D");
    assertThat(updated.user().getVersion()).isGreaterThan(version);
    assertThat(auditCount("USER_UPDATED")).isEqualTo(1);

    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        service.update(
                            actor,
                            id,
                            new UserService.Changes("Stale", null, null, Role.MEMBER, version))))
        .satisfies(e -> assertCode(e, ErrorCode.VERSION_CONFLICT));
  }

  @Test
  void unknownUsersAreNotFound() {
    assertThatThrownBy(() -> inTenant(() -> service.get(UUID.randomUUID())))
        .satisfies(e -> assertCode(e, ErrorCode.NOT_FOUND));
  }

  @Test
  void anAdministratorCannotDisableThemselves() {
    assertThatThrownBy(() -> inTenant(() -> service.disable(actor, admin.user().getId())))
        .satisfies(e -> assertCode(e, ErrorCode.CANNOT_DISABLE_SELF));
  }

  @Test
  void theLastAdministratorCannotBeDisabledOrDemoted() {
    var other = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    Actor otherActor = new Actor(other.user().getId(), other.account().getId(), null);
    assertThatThrownBy(() -> inTenant(() -> service.disable(otherActor, admin.user().getId())))
        .satisfies(e -> assertCode(e, ErrorCode.LAST_ADMIN));
    long version = admin.user().getVersion();
    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        service.update(
                            otherActor,
                            admin.user().getId(),
                            new UserService.Changes("Test", null, null, Role.MEMBER, version))))
        .satisfies(e -> assertCode(e, ErrorCode.LAST_ADMIN));
  }

  @Test
  void aSecondAdministratorMakesDisablingTheFirstPossible() {
    var second = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    Actor secondActor = new Actor(second.user().getId(), second.account().getId(), null);
    var disabled = inTenant(() -> service.disable(secondActor, admin.user().getId()));
    assertThat(disabled.user().getStatus()).isEqualTo(Status.DISABLED);
  }

  @Test
  void disablingAUserRevokesAllSessionsAndReEnablingDoesNotRevive() {
    var target = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var open = sessions.open(tenant, target);
    var second = sessions.open(tenant, target);

    inTenant(() -> service.disable(actor, target.user().getId()));

    for (var s : List.of(open, second)) {
      assertThat(
              owner.queryForObject(
                  "select revoke_reason from session where id = ?", String.class, s.sessionId()))
          .isEqualTo("USER_DISABLED");
    }
    assertThat(auditCount("USER_DISABLED")).isEqualTo(1);

    inTenant(() -> service.enable(actor, target.user().getId()));
    assertThat(inTenant(() -> sessionService.validate(open.sessionId()))).isEmpty();
    assertThat(auditCount("USER_ENABLED")).isEqualTo(1);
  }

  @Test
  void listsOnlyMatchingUsersWithPaging() {
    create("erin.e");
    create("frank.f");
    PageResult<UserView> page = inTenant(() -> service.list("erin", null, 0, 20));
    assertThat(page.items()).extracting(UserView::primaryUsername).containsExactly("erin.e");
    PageResult<UserView> all = inTenant(() -> service.list(null, Status.ACTIVE, 0, 2));
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.items()).hasSize(2);
    assertThat(inTenant(() -> service.list("Alice", Status.DISABLED, 0, 20)).total()).isZero();
  }
}
