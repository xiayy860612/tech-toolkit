package tech.toolkit.tenantcore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
    actor = new Actor(admin.user().getId(), "203.0.113.5");
  }

  private <T> T inTenant(Supplier<T> action) {
    return TenantContext.callAs(tenant, action);
  }

  private UserService.Created create(String username) {
    return inTenant(
        () -> service.create(actor, new UserService.NewUser("Alice", Role.MEMBER, username, null)));
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
  void createsAUserWithBuiltInUsernameAndPasswordThatMustBeChanged() {
    UserService.Created created = create("alice.w");

    assertThat(created.user().user().getRole()).isEqualTo(Role.MEMBER);
    assertThat(created.user().user().getUsername()).isEqualTo("alice.w");
    assertThat(created.user().user().getUsernameNormalized()).isEqualTo("alice.w");
    assertThat(created.user().user().getPasswordHash()).startsWith("$argon2id$");
    assertThat(created.user().user().isMustChangePassword()).isTrue();
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
                    new UserService.NewUser("Bob", Role.MEMBER, "bob.b", "Giv3n-Passw0rd!")));
    assertThat(created.temporaryPassword()).isNull();
    assertThat(created.user().user().isMustChangePassword()).isTrue();
  }

  @Test
  void aWeakSuppliedTemporaryPasswordIsRejected() {
    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        service.create(
                            actor, new UserService.NewUser("Bob", Role.MEMBER, "bob.c", "weak"))))
        .satisfies(e -> assertCode(e, ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void validatesDisplayNameAndUsername() {
    for (UserService.NewUser bad :
        List.of(
            new UserService.NewUser("", Role.MEMBER, "okname", null),
            new UserService.NewUser("x".repeat(51), Role.MEMBER, "okname", null),
            new UserService.NewUser("N", Role.MEMBER, "ab", null),
            new UserService.NewUser("N", Role.MEMBER, "-leading", null),
            new UserService.NewUser("N", Role.MEMBER, "has space", null))) {
      assertThatThrownBy(() -> inTenant(() -> service.create(actor, bad)))
          .satisfies(e -> assertCode(e, ErrorCode.VALIDATION_FAILED));
    }
    var ok =
        inTenant(
            () -> service.create(actor, new UserService.NewUser("N", Role.MEMBER, "okname", null)));
    assertThat(ok.user().user().getDisplayName()).isEqualTo("N");
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
    Long id = created.user().user().getId();
    long version = created.user().user().getVersion();

    var updated =
        inTenant(
            () ->
                service.update(actor, id, new UserService.Changes("Dave D", Role.MEMBER, version)));
    assertThat(updated.user().getDisplayName()).isEqualTo("Dave D");
    assertThat(updated.user().getVersion()).isGreaterThan(version);
    assertThat(auditCount("USER_UPDATED")).isEqualTo(1);

    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        service.update(
                            actor, id, new UserService.Changes("Stale", Role.MEMBER, version))))
        .satisfies(e -> assertCode(e, ErrorCode.VERSION_CONFLICT));
  }

  @Test
  void unknownUsersAreNotFound() {
    assertThatThrownBy(() -> inTenant(() -> service.get(Long.MAX_VALUE)))
        .satisfies(e -> assertCode(e, ErrorCode.NOT_FOUND));
  }

  @Test
  void anAdministratorCannotDisableThemselves() {
    assertThatThrownBy(() -> inTenant(() -> service.disable(actor, admin.user().getId())))
        .satisfies(e -> assertCode(e, ErrorCode.CANNOT_DISABLE_SELF));
  }

  @Test
  void anAdministratorCannotChangeTheirOwnRoleButCanEditTheirOtherFields() {
    var second = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    Long id = admin.user().getId();
    long version = admin.user().getVersion();
    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        service.update(
                            actor, id, new UserService.Changes("Me", Role.MEMBER, version))))
        .satisfies(e -> assertCode(e, ErrorCode.CANNOT_CHANGE_OWN_ROLE));
    var memberActor = new Actor(second.user().getId(), null);
    // 升级自己同样不可：先由另一名管理员把 second 降为 MEMBER，再让 second 自己升级
    inTenant(
        () ->
            service.update(
                actor,
                second.user().getId(),
                new UserService.Changes("Second", Role.MEMBER, second.user().getVersion())));
    long secondVersion = inTenant(() -> service.get(second.user().getId())).user().getVersion();
    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        service.update(
                            memberActor,
                            second.user().getId(),
                            new UserService.Changes("Second", Role.TENANT_ADMIN, secondVersion))))
        .satisfies(e -> assertCode(e, ErrorCode.CANNOT_CHANGE_OWN_ROLE));
    // 角色不变时其它字段照常保存
    var edited =
        inTenant(
            () ->
                service.update(
                    actor, id, new UserService.Changes("Renamed", Role.TENANT_ADMIN, version)));
    assertThat(edited.user().getDisplayName()).isEqualTo("Renamed");
  }

  @Test
  void theLastAdministratorCannotBeDisabledOrDemoted() {
    var other = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    Actor otherActor = new Actor(other.user().getId(), null);
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
                            new UserService.Changes("Test", Role.MEMBER, version))))
        .satisfies(e -> assertCode(e, ErrorCode.LAST_ADMIN));
  }

  @Test
  void aSecondAdministratorMakesDisablingTheFirstPossible() {
    var second = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    Actor secondActor = new Actor(second.user().getId(), null);
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
  void unlockClearsFailedAttemptsAndTheLock() {
    var target = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    owner.update(
        "update app_user set failed_attempts = 3, locked_until = now() + interval '10 minutes'"
            + " where id = ?",
        target.user().getId());

    var unlocked = inTenant(() -> service.unlock(actor, target.user().getId()));

    assertThat(unlocked.user().getFailedAttempts()).isZero();
    assertThat(unlocked.user().getLockedUntil()).isNull();
    assertThat(auditCount("USER_UNLOCKED")).isEqualTo(1);
  }

  @Test
  void resetPasswordWritesANewHashForcesChangeAndRevokesSessions() {
    var target = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var open = sessions.open(tenant, target);
    String oldHash =
        owner.queryForObject(
            "select password_hash from app_user where id = ?", String.class, target.user().getId());

    var reset = inTenant(() -> service.resetPassword(actor, target.user().getId(), null));

    assertThat(reset.temporaryPassword()).isNotBlank();
    assertThat(reset.user().user().isMustChangePassword()).isTrue();
    assertThat(
            owner.queryForObject(
                "select password_hash from app_user where id = ?",
                String.class,
                target.user().getId()))
        .isNotEqualTo(oldHash);
    assertThat(
            owner.queryForObject(
                "select revoke_reason from session where id = ?", String.class, open.sessionId()))
        .isEqualTo("PASSWORD_RESET");
    assertThat(auditCount("PASSWORD_RESET")).isEqualTo(1);
    assertThat(
            owner.queryForObject(
                "select detail::text from audit_log where action='PASSWORD_RESET' and tenant_id = ?",
                String.class,
                tenant))
        .doesNotContain(reset.temporaryPassword());

    var supplied =
        inTenant(() -> service.resetPassword(actor, target.user().getId(), "Giv3n-Passw0rd!"));
    assertThat(supplied.temporaryPassword()).isNull();
    assertThatThrownBy(
            () -> inTenant(() -> service.resetPassword(actor, target.user().getId(), "weak")))
        .satisfies(e -> assertCode(e, ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void anAdministratorMayResetTheirOwnPassword() {
    var reset = inTenant(() -> service.resetPassword(actor, admin.user().getId(), null));
    assertThat(reset.temporaryPassword()).isNotBlank();
  }

  @Test
  void unknownUsersCannotBeUnlockedOrReset() {
    assertThatThrownBy(() -> inTenant(() -> service.unlock(actor, Long.MAX_VALUE)))
        .satisfies(e -> assertCode(e, ErrorCode.NOT_FOUND));
    assertThatThrownBy(() -> inTenant(() -> service.resetPassword(actor, Long.MAX_VALUE, null)))
        .satisfies(e -> assertCode(e, ErrorCode.NOT_FOUND));
  }

  @Test
  void listsOnlyMatchingUsersWithPaging() {
    create("erin.e");
    create("frank.f");
    PageResult<UserView> page = inTenant(() -> service.list("erin", null, 0, 20));
    assertThat(page.items()).extracting(v -> v.user().getUsername()).containsExactly("erin.e");
    PageResult<UserView> all = inTenant(() -> service.list(null, Status.ACTIVE, 0, 2));
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.items()).hasSize(2);
    assertThat(inTenant(() -> service.list("Alice", Status.DISABLED, 0, 20)).total()).isZero();
  }
}
