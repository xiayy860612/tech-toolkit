package tech.toolkit.tenantcore.support;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.platform.PasswordHasher;
import tech.toolkit.tenantcore.platform.TenantContext;

/** 测试夹具：在指定租户里创建带用户名与密码的 User（用户名密码内置于 User）。 */
@Component
public class TestAccounts {

  public record Created(AppUser user, String username, String password) {}

  private final AppUserRepository users;
  private final PasswordHasher hasher;
  private final TransactionTemplate tx;

  TestAccounts(AppUserRepository users, PasswordHasher hasher, TransactionTemplate tx) {
    this.users = users;
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
                  AppUser user =
                      users.save(
                          new AppUser(
                              "Test " + username,
                              role,
                              username,
                              hasher.hash(password),
                              mustChange));
                  return new Created(user, username, password);
                }));
  }

  public static String uniqueName() {
    return "u" + java.util.UUID.randomUUID().toString().substring(0, 8);
  }
}
