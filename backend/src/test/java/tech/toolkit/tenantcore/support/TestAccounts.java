package tech.toolkit.tenantcore.support;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.identity.Account;
import tech.toolkit.tenantcore.identity.AccountRepository;
import tech.toolkit.tenantcore.identity.AccountType;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.PasswordCredential;
import tech.toolkit.tenantcore.identity.PasswordCredentialRepository;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.platform.PasswordHasher;
import tech.toolkit.tenantcore.platform.TenantContext;

/** 测试夹具：在指定租户里创建 User + Account + 密码凭证。 */
@Component
public class TestAccounts {

  public record Created(AppUser user, Account account, String username, String password) {}

  private final AppUserRepository users;
  private final AccountRepository accounts;
  private final PasswordCredentialRepository credentials;
  private final PasswordHasher hasher;
  private final TransactionTemplate tx;

  TestAccounts(
      AppUserRepository users,
      AccountRepository accounts,
      PasswordCredentialRepository credentials,
      PasswordHasher hasher,
      TransactionTemplate tx) {
    this.users = users;
    this.accounts = accounts;
    this.credentials = credentials;
    this.hasher = hasher;
    this.tx = tx;
  }

  public Created create(
      long tenant, String username, String password, Role role, boolean mustChange) {
    return TenantContext.callAs(
        tenant,
        () ->
            tx.execute(
                s -> {
                  AppUser user = users.save(new AppUser("Test " + username, null, null, role));
                  Account account =
                      accounts.save(
                          new Account(user.getId(), AccountType.USERNAME_PASSWORD, username));
                  credentials.save(
                      new PasswordCredential(account.getId(), hasher.hash(password), mustChange));
                  return new Created(user, account, username, password);
                }));
  }

  public static String uniqueName() {
    return "u" + java.util.UUID.randomUUID().toString().substring(0, 8);
  }
}
