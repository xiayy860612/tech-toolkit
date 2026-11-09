package tech.toolkit.tenantcore.tenant;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import tech.toolkit.tenantcore.audit.AuditRecorder;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;

/** Creates and looks up Tenants. Creation is idempotent on the Tenant Slug. */
public class TenantService implements TenantLookup {

  private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{2,31}$");

  private final TenantRepository tenants;
  private final SubscriptionRepository subscriptions;
  private final AuditRecorder audit;
  private final Clock clock;

  public TenantService(
      TenantRepository tenants,
      SubscriptionRepository subscriptions,
      AuditRecorder audit,
      Clock clock) {
    this.tenants = tenants;
    this.subscriptions = subscriptions;
    this.audit = audit;
    this.clock = clock;
  }

  public record TenantCreation(Result result, Tenant tenant) {
    public enum Result {
      CREATED,
      EXISTS
    }
  }

  public TenantCreation create(String slug, String name, String actor) {
    if (slug == null || !SLUG.matcher(slug).matches()) {
      throw new DomainException(
          ErrorCode.VALIDATION, "Tenant Slug must match ^[a-z][a-z0-9-]{2,31}$");
    }
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty() || trimmed.length() > 100) {
      throw new DomainException(ErrorCode.VALIDATION, "Tenant name must be 1-100 characters");
    }
    Optional<Tenant> existing = tenants.findBySlug(slug);
    if (existing.isPresent()) {
      return existing(existing.get(), trimmed, actor);
    }
    Tenant tenant = new Tenant(UUID.randomUUID(), slug, trimmed, "ACTIVE", clock.instant());
    try {
      tenants.insert(tenant);
    } catch (DuplicateKeyException race) {
      return existing(tenants.findBySlug(slug).orElseThrow(), trimmed, actor);
    }
    audit.success(actor, "tenant.create", tenant.id(), "tenant:" + slug);
    return new TenantCreation(TenantCreation.Result.CREATED, tenant);
  }

  private TenantCreation existing(Tenant tenant, String requestedName, String actor) {
    if (!tenant.name().equals(requestedName)) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "Tenant '" + tenant.slug() + "' already exists with a different name");
    }
    audit.noop(actor, "tenant.create", tenant.id(), "tenant:" + tenant.slug());
    return new TenantCreation(TenantCreation.Result.EXISTS, tenant);
  }

  @Override
  public Optional<Tenant> findBySlug(String slug) {
    return tenants.findBySlug(slug);
  }

  @Override
  public Optional<Tenant> findById(UUID id) {
    return tenants.findById(id);
  }

  @Override
  public boolean hasSubscription(UUID tenantId, String moduleCode) {
    return subscriptions.exists(tenantId, moduleCode);
  }
}
