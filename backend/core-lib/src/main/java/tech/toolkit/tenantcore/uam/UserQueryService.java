package tech.toolkit.tenantcore.uam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tech.toolkit.tenantcore.common.Page;
import tech.toolkit.tenantcore.tenant.TenantDetailService.TenantUserDirectory;
import tech.toolkit.tenantcore.tenant.TenantDetailService.UserSummary;
import tech.toolkit.tenantcore.tenant.TenantQueryService;

/**
 * Read-only views of Users and their Accounts. Never exposes credential data. Tenant Slugs come
 * from the tenant package through its query service, not from a join.
 */
public class UserQueryService implements TenantUserDirectory {

  /** One row of the User list in the console. */
  public record UserView(
      String tenantUserId,
      String displayName,
      List<String> accountUsernames,
      String tenantSlug,
      String status,
      java.time.Instant createdAt) {}

  private final UserRepository users;
  private final AccountRepository accounts;
  private final LoginMethodRepository loginMethods;
  private final TenantQueryService tenantQueries;

  public UserQueryService(
      UserRepository users,
      AccountRepository accounts,
      LoginMethodRepository loginMethods,
      TenantQueryService tenantQueries) {
    this.users = users;
    this.accounts = accounts;
    this.loginMethods = loginMethods;
    this.tenantQueries = tenantQueries;
  }

  public Page<UserView> users(int page, int size) {
    int p = Page.normalizePage(page);
    int s = Page.normalizeSize(size);
    List<User> rows = users.page(p * s, s);
    Map<UUID, List<String>> usernames = usernamesByUser(rows);
    Map<UUID, String> slugs =
        tenantQueries.slugsByIds(rows.stream().map(User::tenantId).distinct().toList());
    List<UserView> views =
        rows.stream()
            .map(
                u ->
                    new UserView(
                        u.tenantUserId(),
                        u.displayName(),
                        usernames.getOrDefault(u.id(), List.of()),
                        slugs.getOrDefault(u.tenantId(), ""),
                        u.status(),
                        u.createdAt()))
            .toList();
    return new Page<>(views, p, s, users.count());
  }

  @Override
  public List<UserSummary> usersOf(UUID tenantId) {
    List<User> rows = users.listByTenant(tenantId);
    Map<UUID, List<String>> usernames = usernamesByUser(rows);
    return rows.stream()
        .map(
            u ->
                new UserSummary(
                    u.tenantUserId(), u.displayName(), usernames.getOrDefault(u.id(), List.of())))
        .toList();
  }

  @Override
  public List<String> loginMethodsOf(UUID tenantId) {
    return loginMethods.methodsOf(tenantId);
  }

  private Map<UUID, List<String>> usernamesByUser(List<User> rows) {
    Map<UUID, List<String>> result = new HashMap<>();
    accounts
        .findByUsers(rows.stream().map(User::id).toList())
        .forEach(a -> result.computeIfAbsent(a.userId(), k -> new ArrayList<>()).add(a.username()));
    return result;
  }
}
