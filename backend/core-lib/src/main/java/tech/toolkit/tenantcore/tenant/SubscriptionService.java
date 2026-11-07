package tech.toolkit.tenantcore.tenant;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import tech.toolkit.tenantcore.audit.AuditRecorder;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;

/** Subscribes Tenants to Modules. MVP: the only Module is {@code platform}. */
public class SubscriptionService {

  public static final Set<String> MODULES = Set.of("platform");

  public enum SubscriptionResult {
    SUBSCRIBED,
    ALREADY_SUBSCRIBED
  }

  private final TenantRepository tenants;
  private final SubscriptionRepository subscriptions;
  private final AuditRecorder audit;
  private final Clock clock;

  public SubscriptionService(
      TenantRepository tenants,
      SubscriptionRepository subscriptions,
      AuditRecorder audit,
      Clock clock) {
    this.tenants = tenants;
    this.subscriptions = subscriptions;
    this.audit = audit;
    this.clock = clock;
  }

  public SubscriptionResult add(String tenantSlug, String moduleCode, String actor) {
    Tenant tenant =
        tenants
            .findBySlug(tenantSlug)
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.NOT_FOUND, "Tenant '" + tenantSlug + "' does not exist"));
    if (moduleCode == null || !MODULES.contains(moduleCode)) {
      throw new DomainException(ErrorCode.NOT_FOUND, "Module '" + moduleCode + "' does not exist");
    }
    String target = "tenant:" + tenantSlug + " module:" + moduleCode;
    if (subscriptions.exists(tenant.id(), moduleCode)) {
      audit.noop(actor, "subscription.add", tenant.id(), target);
      return SubscriptionResult.ALREADY_SUBSCRIBED;
    }
    try {
      subscriptions.insert(
          new ModuleSubscription(
              UUID.randomUUID(), tenant.id(), moduleCode, "SUBSCRIBED", clock.instant()));
    } catch (DuplicateKeyException race) {
      audit.noop(actor, "subscription.add", tenant.id(), target);
      return SubscriptionResult.ALREADY_SUBSCRIBED;
    }
    audit.success(actor, "subscription.add", tenant.id(), target);
    return SubscriptionResult.SUBSCRIBED;
  }
}
