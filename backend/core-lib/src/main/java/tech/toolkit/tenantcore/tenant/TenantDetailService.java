package tech.toolkit.tenantcore.tenant;

import java.util.List;
import java.util.UUID;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;

/** Everything the Bootstrap CLI prints for {@code tenant show}; contains no credential data. */
public class TenantDetailService {

  /** Implemented by the uam package, so this package never reads uam_* tables. */
  public interface TenantUserDirectory {
    List<UserSummary> usersOf(UUID tenantId);

    List<String> loginMethodsOf(UUID tenantId);
  }

  public record UserSummary(
      String tenantUserId, String displayName, List<String> accountUsernames) {}

  public record TenantDetail(
      Tenant tenant, List<String> loginMethods, List<UserSummary> users, List<String> modules) {}

  private final TenantRepository tenants;
  private final SubscriptionRepository subscriptions;
  private final TenantUserDirectory directory;

  public TenantDetailService(
      TenantRepository tenants,
      SubscriptionRepository subscriptions,
      TenantUserDirectory directory) {
    this.tenants = tenants;
    this.subscriptions = subscriptions;
    this.directory = directory;
  }

  public TenantDetail show(String tenantSlug) {
    Tenant tenant =
        tenants
            .findBySlug(tenantSlug)
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.NOT_FOUND, "Tenant '" + tenantSlug + "' does not exist"));
    return new TenantDetail(
        tenant,
        directory.loginMethodsOf(tenant.id()),
        directory.usersOf(tenant.id()),
        subscriptions.modulesOf(tenant.id()));
  }
}
