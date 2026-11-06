package tech.toolkit.tenantcore.uam;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import tech.toolkit.tenantcore.audit.AuditRecorder;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.common.IdGenerator;
import tech.toolkit.tenantcore.tenant.Tenant;
import tech.toolkit.tenantcore.tenant.TenantLookup;

/**
 * Creates Users. A User is a person; it cannot log in until an Account is attached. The Bootstrap
 * CLI treats the same Display Name in the same Tenant as the same User (idempotent).
 */
public class UserService {

  public record UserCreation(Result result, User user) {
    public enum Result {
      CREATED,
      EXISTS
    }
  }

  private final TenantLookup tenants;
  private final UserRepository users;
  private final IdGenerator ids;
  private final AuditRecorder audit;
  private final Clock clock;

  public UserService(
      TenantLookup tenants,
      UserRepository users,
      IdGenerator ids,
      AuditRecorder audit,
      Clock clock) {
    this.tenants = tenants;
    this.users = users;
    this.ids = ids;
    this.audit = audit;
    this.clock = clock;
  }

  public UserCreation create(String tenantSlug, String displayName, String actor) {
    Tenant tenant =
        tenants
            .findBySlug(tenantSlug)
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.NOT_FOUND, "Tenant '" + tenantSlug + "' does not exist"));
    String name = displayName == null ? "" : displayName.trim();
    if (name.isEmpty() || name.length() > 100) {
      throw new DomainException(ErrorCode.VALIDATION, "Display Name must be 1-100 characters");
    }
    Optional<User> existing = users.findByTenantAndDisplayName(tenant.id(), name);
    if (existing.isPresent()) {
      audit.noop(actor, "user.create", tenant.id(), "user:" + existing.get().tenantUserId());
      return new UserCreation(UserCreation.Result.EXISTS, existing.get());
    }
    User user =
        new User(
            UUID.randomUUID(), ids.tenantUserId(), tenant.id(), name, "ACTIVE", clock.instant());
    users.insert(user);
    audit.success(actor, "user.create", tenant.id(), "user:" + user.tenantUserId());
    return new UserCreation(UserCreation.Result.CREATED, user);
  }
}
