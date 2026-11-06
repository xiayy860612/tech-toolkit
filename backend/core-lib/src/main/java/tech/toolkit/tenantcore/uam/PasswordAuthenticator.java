package tech.toolkit.tenantcore.uam;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.tenant.Tenant;
import tech.toolkit.tenantcore.tenant.TenantLookup;

/**
 * Verifies Tenant Slug + username + password.
 *
 * <p>Every failure looks identical to the caller (same error, same message, similar cost), so an
 * attacker learns nothing about tenants, accounts or lockout. The only distinct answer,
 * MODULE_NOT_SUBSCRIBED, is given strictly after the password has been verified.
 */
public class PasswordAuthenticator implements Authenticator {

  public static final String INVALID_MESSAGE =
      "Invalid username or password; if you failed several times, try again later";
  public static final String PLATFORM_MODULE = "platform";

  private final TenantLookup tenants;
  private final LoginMethodRepository loginMethods;
  private final UserRepository users;
  private final AccountRepository accounts;
  private final PasswordCredentialRepository credentials;
  private final PasswordHasher hasher;
  private final LoginAttemptPolicy attempts;
  private final Clock clock;

  public PasswordAuthenticator(
      TenantLookup tenants,
      LoginMethodRepository loginMethods,
      UserRepository users,
      AccountRepository accounts,
      PasswordCredentialRepository credentials,
      PasswordHasher hasher,
      LoginAttemptPolicy attempts,
      Clock clock) {
    this.tenants = tenants;
    this.loginMethods = loginMethods;
    this.users = users;
    this.accounts = accounts;
    this.credentials = credentials;
    this.hasher = hasher;
    this.attempts = attempts;
    this.clock = clock;
  }

  @Override
  public AuthenticatedIdentity authenticate(LoginRequest request) {
    String password = request.password() == null ? "" : request.password();
    String username =
        request.username() == null ? "" : request.username().trim().toLowerCase(Locale.ROOT);

    Optional<Tenant> tenant =
        request.tenantSlug() == null ? Optional.empty() : tenants.findBySlug(request.tenantSlug());
    Optional<Account> account =
        tenant.flatMap(
            t -> accounts.findByTenantAndUsername(t.id(), LoginMethodService.PASSWORD, username));
    Optional<User> user = account.flatMap(a -> users.findById(a.userId()));
    Optional<PasswordCredential> credential =
        account.flatMap(a -> credentials.findByAccountId(a.id()));

    boolean eligible =
        tenant.isPresent()
            && "ACTIVE".equals(tenant.get().status())
            && loginMethods.isEnabled(tenant.get().id(), LoginMethodService.PASSWORD)
            && user.isPresent()
            && "ACTIVE".equals(user.get().status())
            && credential.isPresent();
    Instant now = clock.instant();
    if (!eligible || attempts.isLocked(credential.get(), now)) {
      hasher.dummyMatches(password); // same cost as a real check
      throw invalid();
    }

    PasswordCredential stored = credential.get();
    if (!hasher.matches(password, stored.passwordHash())) {
      LoginAttemptPolicy.Failure failure = attempts.afterFailure(stored, now);
      credentials.markFailure(stored.accountId(), failure.failedCount(), failure.lockedUntil());
      throw invalid();
    }
    credentials.clearFailures(stored.accountId());

    if (!tenants.hasSubscription(tenant.get().id(), PLATFORM_MODULE)) {
      throw new DomainException(
          ErrorCode.MODULE_NOT_SUBSCRIBED, "The Tenant has no access to the platform Module");
    }
    return new AuthenticatedIdentity(
        tenant.get(), user.get(), account.get(), stored.mustChangePassword());
  }

  private static DomainException invalid() {
    return new DomainException(ErrorCode.INVALID_CREDENTIALS, INVALID_MESSAGE);
  }
}
