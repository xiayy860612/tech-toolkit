package tech.toolkit.tenantcore.uam;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.audit.AuditRecorder;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.tenant.Tenant;
import tech.toolkit.tenantcore.tenant.TenantLookup;

/**
 * Creates the username/password Account of a User. The initial password is hashed immediately and
 * the Account must change it at first login. Idempotent: an existing Account is never modified.
 */
public class AccountService {

  private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
  private static final String PASSWORD = LoginMethodService.PASSWORD;

  public record AccountCreation(Result result, Account account) {
    public enum Result {
      CREATED,
      EXISTS
    }
  }

  private final TenantLookup tenants;
  private final LoginMethodRepository loginMethods;
  private final UserRepository users;
  private final AccountRepository accounts;
  private final PasswordCredentialRepository credentials;
  private final PasswordPolicy policy;
  private final PasswordHasher hasher;
  private final AuditRecorder audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  public AccountService(
      TenantLookup tenants,
      LoginMethodRepository loginMethods,
      UserRepository users,
      AccountRepository accounts,
      PasswordCredentialRepository credentials,
      PasswordPolicy policy,
      PasswordHasher hasher,
      AuditRecorder audit,
      TransactionTemplate tx,
      Clock clock) {
    this.tenants = tenants;
    this.loginMethods = loginMethods;
    this.users = users;
    this.accounts = accounts;
    this.credentials = credentials;
    this.policy = policy;
    this.hasher = hasher;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  public AccountCreation createPasswordAccount(
      String tenantSlug, String tenantUserId, String username, String password, String actor) {
    Tenant tenant =
        tenants
            .findBySlug(tenantSlug)
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.NOT_FOUND, "Tenant '" + tenantSlug + "' does not exist"));
    if (!loginMethods.isEnabled(tenant.id(), PASSWORD)) {
      throw new DomainException(
          ErrorCode.NOT_FOUND,
          "Password login is not enabled for Tenant '" + tenantSlug + "'; enable it first");
    }
    User user =
        users
            .findByTenantUserId(tenantUserId)
            .filter(u -> u.tenantId().equals(tenant.id()))
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.NOT_FOUND,
                        "User '"
                            + tenantUserId
                            + "' does not exist in Tenant '"
                            + tenantSlug
                            + "'"));
    String name = normalize(username);

    Optional<Account> own = accounts.findByUserAndType(user.id(), PASSWORD);
    if (own.isPresent()) {
      if (!own.get().username().equals(name)) {
        throw new DomainException(
            ErrorCode.CONFLICT, "User already has a password Account with another username");
      }
      audit.noop(actor, "account.create", tenant.id(), "account:" + name);
      return new AccountCreation(AccountCreation.Result.EXISTS, own.get());
    }
    if (accounts.findByTenantAndUsername(tenant.id(), PASSWORD, name).isPresent()) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "username '" + name + "' is already used in Tenant '" + tenantSlug + "'");
    }
    policy.validate(password, name);

    Account account =
        new Account(UUID.randomUUID(), user.id(), tenant.id(), PASSWORD, name, clock.instant());
    String hash = hasher.hash(password);
    try {
      tx.executeWithoutResult(
          status -> {
            accounts.insert(account);
            credentials.insert(
                new PasswordCredential(account.id(), hash, true, clock.instant(), 0, null));
          });
    } catch (DuplicateKeyException race) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "username '" + name + "' is already used in Tenant '" + tenantSlug + "'");
    }
    audit.success(actor, "account.create", tenant.id(), "account:" + name);
    return new AccountCreation(AccountCreation.Result.CREATED, account);
  }

  private static String normalize(String username) {
    String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    if (!USERNAME.matcher(name).matches()) {
      throw new DomainException(
          ErrorCode.VALIDATION, "username must match ^[a-z0-9][a-z0-9._-]{2,63}$");
    }
    return name;
  }
}
