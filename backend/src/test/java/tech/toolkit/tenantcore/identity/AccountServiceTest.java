package tech.toolkit.tenantcore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.toolkit.tenantcore.platform.Actor;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestSessions;

class AccountServiceTest extends AbstractPostgresIT {

  private static final AtomicLong TENANTS = new AtomicLong(200);
  private static final String PW = "Str0ng-Passw0rd";

  @Autowired AccountService service;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;
  @Autowired AccountRepository accounts;
  @Autowired PasswordCredentialRepository credentials;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
  private long tenant;
  private Actor actor;
  private TestAccounts.Created target; // 目标 User 的首个 Account

  @BeforeEach
  void setUp() {
    tenant = TENANTS.incrementAndGet();
    var admin = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    actor = new Actor(admin.user().getId(), admin.account().getId(), "203.0.113.5");
    target = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
  }

  private <T> T inTenant(Supplier<T> a) {
    return TenantContext.callAs(tenant, a);
  }

  private void run(Runnable a) {
    TenantContext.runAs(tenant, a);
  }

  private UUID userId() {
    return target.user().getId();
  }

  private int audits(String action) {
    return owner.queryForObject(
        "select count(*) from audit_log where tenant_id = ? and action = ?",
        Integer.class,
        tenant,
        action);
  }

  private String revokeReason(UUID sessionId) {
    return owner.queryForObject(
        "select revoke_reason from session where id = ?", String.class, sessionId);
  }

  private static void assertCode(Throwable t, ErrorCode code) {
    assertThat(t)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  private AccountService.Created second(String username) {
    return inTenant(() -> service.create(actor, userId(), username, null));
  }

  @Test
  void addsASecondAccountWithAGeneratedPasswordAndMustChange() {
    var created = second("Second.Acct");
    assertThat(created.temporaryPassword()).isNotBlank();
    assertThat(created.account().mustChangePassword()).isTrue();
    assertThat(created.account().account().getIdentifierNormalized()).isEqualTo("second.acct");
    assertThat(inTenant(() -> service.list(userId()))).hasSize(2);
    assertThat(audits("ACCOUNT_CREATED")).isEqualTo(1);
  }

  @Test
  void usernameRulesAndUniquenessApply() {
    second("taken.name");
    assertThatThrownBy(() -> second("TAKEN.NAME"))
        .satisfies(e -> assertCode(e, ErrorCode.USERNAME_TAKEN));
    assertThatThrownBy(() -> second("x"))
        .satisfies(e -> assertCode(e, ErrorCode.VALIDATION_FAILED));
    assertThatThrownBy(
            () -> inTenant(() -> service.create(actor, UUID.randomUUID(), "valid.name", null)))
        .satisfies(e -> assertCode(e, ErrorCode.NOT_FOUND));
  }

  @Test
  void disablingTheLastAvailableAccountNeedsConfirmation() {
    UUID only = target.account().getId();
    assertThatThrownBy(() -> inTenant(() -> service.disable(actor, userId(), only, false)))
        .satisfies(e -> assertCode(e, ErrorCode.LAST_ACTIVE_ACCOUNT_CONFIRM_REQUIRED));
    assertThatThrownBy(() -> run(() -> service.delete(actor, userId(), only, false)))
        .satisfies(e -> assertCode(e, ErrorCode.LAST_ACTIVE_ACCOUNT_CONFIRM_REQUIRED));
    var disabled = inTenant(() -> service.disable(actor, userId(), only, true));
    assertThat(disabled.account().getStatus()).isEqualTo(Status.DISABLED);
  }

  @Test
  void disablingOneOfTwoAccountsNeedsNoConfirmationAndRevokesOnlyItsSessions() {
    var other = second("other.acct");
    var open = sessions.open(tenant, target);
    UUID firstAccount = target.account().getId();

    inTenant(() -> service.disable(actor, userId(), firstAccount, false));

    assertThat(revokeReason(open.sessionId())).isEqualTo("ACCOUNT_DISABLED");
    assertThat(audits("ACCOUNT_DISABLED")).isEqualTo(1);
    inTenant(() -> service.enable(actor, userId(), firstAccount));
    assertThat(audits("ACCOUNT_ENABLED")).isEqualTo(1);
    assertThat(other.account().account().getStatus()).isEqualTo(Status.ACTIVE);
  }

  @Test
  void deletingAnAccountRemovesItAndItsCredentialAndRevokesSessions() {
    var extra = second("to.remove");
    UUID id = extra.account().account().getId();
    var open =
        sessions.open(
            tenant,
            new TestAccounts.Created(target.user(), extra.account().account(), "to.remove", "x"));

    run(() -> service.delete(actor, userId(), id, false));

    Optional<Account> gone = inTenant(() -> tx.execute(s -> accounts.findById(id)));
    Optional<PasswordCredential> goneCredential =
        inTenant(() -> tx.execute(s -> credentials.findById(id)));
    assertThat(gone).isEmpty();
    assertThat(goneCredential).isEmpty();
    assertThat(revokeReason(open.sessionId())).isEqualTo("ACCOUNT_DELETED");
    assertThat(audits("ACCOUNT_DELETED")).isEqualTo(1);
  }

  @Test
  void resetPasswordWritesANewHashForcesChangeAndRevokesSessions() {
    var open = sessions.open(tenant, target);
    String oldHash =
        owner.queryForObject(
            "select password_hash from password_credential where account_id = ?",
            String.class,
            target.account().getId());

    var reset =
        inTenant(() -> service.resetPassword(actor, userId(), target.account().getId(), null));

    assertThat(reset.temporaryPassword()).isNotBlank();
    assertThat(reset.account().mustChangePassword()).isTrue();
    assertThat(
            owner.queryForObject(
                "select password_hash from password_credential where account_id = ?",
                String.class,
                target.account().getId()))
        .isNotEqualTo(oldHash);
    assertThat(revokeReason(open.sessionId())).isEqualTo("PASSWORD_RESET");
    assertThat(audits("PASSWORD_RESET")).isEqualTo(1);
    assertThat(
            owner.queryForObject(
                "select detail::text from audit_log where action='PASSWORD_RESET'"
                    + " and tenant_id = ?",
                String.class,
                tenant))
        .doesNotContain(reset.temporaryPassword());

    var supplied =
        inTenant(
            () ->
                service.resetPassword(
                    actor, userId(), target.account().getId(), "Giv3n-Passw0rd!"));
    assertThat(supplied.temporaryPassword()).isNull();
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> service.resetPassword(actor, userId(), target.account().getId(), "weak")))
        .satisfies(e -> assertCode(e, ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void unlockClearsFailedAttemptsAndLock() {
    TenantContext.runAs(
        tenant,
        () ->
            tx.executeWithoutResult(
                s -> {
                  Account a = accounts.findById(target.account().getId()).orElseThrow();
                  a.setFailedAttempts(3);
                  a.setLockedUntil(Instant.now().plusSeconds(600));
                }));
    var unlocked = inTenant(() -> service.unlock(actor, userId(), target.account().getId()));
    assertThat(unlocked.account().getFailedAttempts()).isZero();
    assertThat(unlocked.account().getLockedUntil()).isNull();
    assertThat(audits("ACCOUNT_UNLOCKED")).isEqualTo(1);
  }

  @Test
  void anAccountOfAnotherUserIsNotFound() {
    var stranger = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    assertThatThrownBy(
            () -> inTenant(() -> service.unlock(actor, userId(), stranger.account().getId())))
        .satisfies(e -> assertCode(e, ErrorCode.NOT_FOUND));
  }
}
