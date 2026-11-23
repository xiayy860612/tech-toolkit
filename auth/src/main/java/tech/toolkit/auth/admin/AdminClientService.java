package tech.toolkit.auth.admin;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.auth.audit.AuditEvent;
import tech.toolkit.auth.audit.AuditService;
import tech.toolkit.auth.audit.AuditType;
import tech.toolkit.auth.client.ClientAuthorizations;
import tech.toolkit.auth.client.ClientException;
import tech.toolkit.auth.client.ClientService;
import tech.toolkit.auth.client.ClientService.ClientChanges;
import tech.toolkit.auth.client.ClientService.ClientView;
import tech.toolkit.auth.client.ClientService.NewClient;
import tech.toolkit.auth.client.ClientService.Registered;

/**
 * What an administrator can do to clients (FR-023): register, change, disable, enable and rotate
 * the secret. Disabling also deletes the client's authorizations at once (FR-012); every change is
 * audited with the administrator who made it, and never with a secret.
 */
@Service
public class AdminClientService {

  private final ClientService clients;
  private final ClientAuthorizations authorizations;
  private final AuditService audit;

  public AdminClientService(
      ClientService clients, ClientAuthorizations authorizations, AuditService audit) {
    this.clients = clients;
    this.authorizations = authorizations;
    this.audit = audit;
  }

  @Transactional
  public Registered create(NewClient request, long actorId) {
    Registered registered = translated(() -> clients.register(request));
    record(
        AuditType.CLIENT_CREATED,
        registered.client().clientId(),
        actorId,
        Map.of("type", registered.client().type().name()));
    return registered;
  }

  public List<ClientView> list() {
    return clients.list();
  }

  public ClientView get(String clientId) {
    return translated(() -> clients.get(clientId));
  }

  @Transactional
  public ClientView update(String clientId, ClientChanges changes, long actorId) {
    ClientView updated = translated(() -> clients.update(clientId, changes));
    record(AuditType.CLIENT_UPDATED, clientId, actorId, Map.of("action", "update"));
    return updated;
  }

  @Transactional
  public void disable(String clientId, long actorId) {
    translated(
        () -> {
          clients.setEnabled(clientId, false);
          return null;
        });
    authorizations.revokeByClientId(clientId);
    record(AuditType.CLIENT_UPDATED, clientId, actorId, Map.of("action", "disable"));
  }

  @Transactional
  public void enable(String clientId, long actorId) {
    translated(
        () -> {
          clients.setEnabled(clientId, true);
          return null;
        });
    record(AuditType.CLIENT_UPDATED, clientId, actorId, Map.of("action", "enable"));
  }

  @Transactional
  public String rotateSecret(String clientId, long actorId) {
    String secret = translated(() -> clients.rotateSecret(clientId));
    record(AuditType.CLIENT_UPDATED, clientId, actorId, Map.of("action", "rotate-credentials"));
    return secret;
  }

  private void record(AuditType type, String clientId, long actorId, Map<String, Object> detail) {
    audit.record(
        AuditEvent.of(type, true).actor(String.valueOf(actorId)).clientId(clientId).detail(detail));
  }

  private static <T> T translated(Supplier<T> action) {
    try {
      return action.get();
    } catch (ClientException e) {
      HttpStatus status =
          switch (e.kind()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
          };
      String title =
          switch (e.kind()) {
            case INVALID -> "请求无效";
            case NOT_FOUND -> "client 不存在";
            case CONFLICT -> "clientId 已存在";
          };
      throw new AdminException(status, title, e.getMessage());
    }
  }
}
