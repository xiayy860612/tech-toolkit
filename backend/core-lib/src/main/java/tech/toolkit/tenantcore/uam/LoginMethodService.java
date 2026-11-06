package tech.toolkit.tenantcore.uam;

import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import tech.toolkit.tenantcore.audit.AuditRecorder;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.tenant.Tenant;
import tech.toolkit.tenantcore.tenant.TenantLookup;

/** Enables Login Methods for a Tenant. MVP: only username/password ({@code PASSWORD}). */
public class LoginMethodService {

  public static final String PASSWORD = "PASSWORD";

  public enum EnableResult {
    ENABLED,
    ALREADY_ENABLED
  }

  private final TenantLookup tenants;
  private final LoginMethodRepository methods;
  private final AuditRecorder audit;
  private final Clock clock;

  public LoginMethodService(
      TenantLookup tenants, LoginMethodRepository methods, AuditRecorder audit, Clock clock) {
    this.tenants = tenants;
    this.methods = methods;
    this.audit = audit;
    this.clock = clock;
  }

  public EnableResult enable(String tenantSlug, String type, String actor) {
    Tenant tenant =
        tenants
            .findBySlug(tenantSlug)
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.NOT_FOUND, "Tenant '" + tenantSlug + "' does not exist"));
    if (type == null || !PASSWORD.equalsIgnoreCase(type)) {
      throw new DomainException(
          ErrorCode.VALIDATION, "Unsupported Login Method '" + type + "' (only password)");
    }
    String target = "tenant:" + tenantSlug + " method:" + PASSWORD;
    if (methods.isEnabled(tenant.id(), PASSWORD)) {
      audit.noop(actor, "login-method.enable", tenant.id(), target);
      return EnableResult.ALREADY_ENABLED;
    }
    try {
      methods.insert(tenant.id(), PASSWORD, clock.instant());
    } catch (DuplicateKeyException race) {
      audit.noop(actor, "login-method.enable", tenant.id(), target);
      return EnableResult.ALREADY_ENABLED;
    }
    audit.success(actor, "login-method.enable", tenant.id(), target);
    return EnableResult.ENABLED;
  }
}
