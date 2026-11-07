package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.uam.Authenticator.AuthenticatedIdentity;
import tech.toolkit.tenantcore.uam.Authenticator.LoginRequest;

class PasswordAuthenticatorIT extends PostgresTestBase {

  /** Counts how often the throw-away verification runs (used for unknown accounts). */
  private static final class CountingHasher extends PasswordHasher {
    final AtomicInteger dummyCalls = new AtomicInteger();

    @Override
    public boolean dummyMatches(String password) {
      dummyCalls.incrementAndGet();
      return super.dummyMatches(password);
    }
  }

  private final MutableClock clock = new MutableClock();
  private final CoreComponents core = core(clock);
  private final JdbcClient owner = JdbcClient.create(ownerDataSource());
  private final CountingHasher hasher = new CountingHasher();
  private final PasswordAuthenticator authenticator = authenticator();

  private PasswordAuthenticator authenticator() {
    JdbcClient jdbc = JdbcClient.create(appDataSource());
    return new PasswordAuthenticator(
        core.tenants(),
        new LoginMethodRepository(jdbc),
        new UserRepository(jdbc),
        new AccountRepository(jdbc),
        new PasswordCredentialRepository(jdbc),
        hasher,
        new LoginAttemptPolicy(5, Duration.ofMinutes(15)),
        clock);
  }

  private static void assertCode(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(expected));
  }

  private AuthenticatedIdentity login(String tenant, String username, String password) {
    return authenticator.authenticate(new LoginRequest(tenant, username, password));
  }

  @Test
  void authenticatesTheRightCredentials() {
    Bootstrapped b = bootstrap(core, "acme", "admin1");
    AuthenticatedIdentity identity = login("acme", "Admin1", PASSWORD);
    assertThat(identity.user().id()).isEqualTo(b.user().id());
    assertThat(identity.account().username()).isEqualTo("admin1");
    assertThat(identity.tenant().slug()).isEqualTo("acme");
    assertThat(identity.mustChangePassword()).isTrue();
  }

  @Test
  void wrongPasswordAndUnknownAccountLookTheSame() {
    bootstrap(core, "acme", "admin1");
    DomainException wrong = catchDomain(() -> login("acme", "admin1", "wrong password 1234"));
    DomainException unknownUser = catchDomain(() -> login("acme", "nobody", "wrong password 1234"));
    DomainException unknownTenant =
        catchDomain(() -> login("nope", "admin1", "wrong password 1234"));
    for (DomainException e : new DomainException[] {wrong, unknownUser, unknownTenant}) {
      assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
      assertThat(e.getMessage()).isEqualTo(wrong.getMessage());
    }
    assertThat(wrong.getMessage()).contains("try again later");
  }

  private static DomainException catchDomain(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e;
    }
    throw new AssertionError("expected a DomainException");
  }

  @Test
  void unknownAccountsStillDoTheExpensiveVerification() {
    bootstrap(core, "acme", "admin1");
    int before = hasher.dummyCalls.get();
    assertCode(() -> login("acme", "nobody", PASSWORD), ErrorCode.INVALID_CREDENTIALS);
    assertCode(() -> login("nope", "admin1", PASSWORD), ErrorCode.INVALID_CREDENTIALS);
    assertThat(hasher.dummyCalls.get() - before).isEqualTo(2);
  }

  @Test
  void suspendedTenantDisabledUserAndMissingLoginMethodAreAllInvalidCredentials() {
    bootstrap(core, "acme", "admin1");
    owner.sql("UPDATE core_tenant SET status = 'SUSPENDED'").update();
    assertCode(() -> login("acme", "admin1", PASSWORD), ErrorCode.INVALID_CREDENTIALS);
    owner.sql("UPDATE core_tenant SET status = 'ACTIVE'").update();

    owner.sql("UPDATE uam_user SET status = 'DISABLED'").update();
    assertCode(() -> login("acme", "admin1", PASSWORD), ErrorCode.INVALID_CREDENTIALS);
    owner.sql("UPDATE uam_user SET status = 'ACTIVE'").update();

    owner.sql("DELETE FROM uam_tenant_login_method").update();
    assertCode(() -> login("acme", "admin1", PASSWORD), ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void aTenantWithoutPlatformSubscriptionIsOnlyRevealedToTheRightCredentials() {
    bootstrap(core, "acme", "admin1");
    owner.sql("DELETE FROM core_module_subscription").update();
    assertCode(() -> login("acme", "admin1", "wrong password 1234"), ErrorCode.INVALID_CREDENTIALS);
    assertCode(() -> login("acme", "admin1", PASSWORD), ErrorCode.MODULE_NOT_SUBSCRIBED);
  }

  @Test
  void fiveFailuresLockTheAccountForFifteenMinutesWithoutSayingSo() {
    bootstrap(core, "acme", "admin1");
    for (int i = 0; i < 5; i++) {
      assertCode(
          () -> login("acme", "admin1", "wrong password 1234"), ErrorCode.INVALID_CREDENTIALS);
    }
    // even the right password is refused, with the same message
    DomainException locked = catchDomain(() -> login("acme", "admin1", PASSWORD));
    assertThat(locked.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    assertThat(locked.getMessage()).contains("try again later");

    clock.advance(Duration.ofMinutes(14));
    assertCode(() -> login("acme", "admin1", PASSWORD), ErrorCode.INVALID_CREDENTIALS);
    clock.advance(Duration.ofMinutes(2));
    assertThat(login("acme", "admin1", PASSWORD).account().username()).isEqualTo("admin1");
  }

  @Test
  void aSuccessfulLoginResetsTheFailureCounter() {
    bootstrap(core, "acme", "admin1");
    for (int i = 0; i < 4; i++) {
      assertCode(
          () -> login("acme", "admin1", "wrong password 1234"), ErrorCode.INVALID_CREDENTIALS);
    }
    login("acme", "admin1", PASSWORD);
    assertThat(
            owner
                .sql("SELECT failed_count FROM uam_credential_password")
                .query(Integer.class)
                .single())
        .isZero();
    for (int i = 0; i < 4; i++) {
      assertCode(
          () -> login("acme", "admin1", "wrong password 1234"), ErrorCode.INVALID_CREDENTIALS);
    }
    assertThat(login("acme", "admin1", PASSWORD)).isNotNull(); // 4 < 5: not locked
  }

  @Test
  void nullInputIsInvalidCredentials() {
    assertCode(
        () -> authenticator.authenticate(new LoginRequest(null, null, null)),
        ErrorCode.INVALID_CREDENTIALS);
  }
}
