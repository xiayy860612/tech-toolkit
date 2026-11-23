package tech.toolkit.auth.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.auth.client.ClientService.ClientChanges;
import tech.toolkit.auth.client.ClientService.ClientView;
import tech.toolkit.auth.client.ClientService.NewClient;
import tech.toolkit.auth.client.ClientService.Registered;
import tech.toolkit.auth.client.ClientService.Type;

/** The client part of the admin API, as specified in {@code contracts/admin-api.yaml}. */
@RestController
@RequestMapping("/admin/v1/clients")
class AdminClientController {

  /** Body of {@code POST /clients}. */
  record CreateClient(
      String clientId,
      Type type,
      List<String> redirectUris,
      List<String> postLogoutRedirectUris,
      String backchannelLogoutUri,
      List<String> scopes,
      Integer accessTokenTtlSeconds,
      Integer refreshTokenTtlSeconds) {}

  /** The {@code ClientCreated} response: the client, plus the secret this one time. */
  record ClientCreated(
      String clientId,
      Type type,
      List<String> redirectUris,
      List<String> postLogoutRedirectUris,
      String backchannelLogoutUri,
      List<String> scopes,
      long accessTokenTtlSeconds,
      long refreshTokenTtlSeconds,
      boolean enabled,
      @JsonInclude(JsonInclude.Include.NON_NULL) String clientSecret) {
    @Override
    public String toString() {
      return "ClientCreated[clientId=" + clientId + "]";
    }
  }

  private final AdminClientService service;

  AdminClientController(AdminClientService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<ClientCreated> create(@RequestBody CreateClient body, Authentication admin) {
    Registered registered =
        service.create(
            new NewClient(
                body.clientId(),
                body.type(),
                body.redirectUris(),
                body.postLogoutRedirectUris(),
                body.backchannelLogoutUri(),
                body.scopes(),
                body.accessTokenTtlSeconds(),
                body.refreshTokenTtlSeconds()),
            actor(admin));
    ClientView c = registered.client();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new ClientCreated(
                c.clientId(),
                c.type(),
                c.redirectUris(),
                c.postLogoutRedirectUris(),
                c.backchannelLogoutUri(),
                c.scopes(),
                c.accessTokenTtlSeconds(),
                c.refreshTokenTtlSeconds(),
                c.enabled(),
                registered.clientSecret()));
  }

  @GetMapping
  List<ClientView> list() {
    return service.list();
  }

  @GetMapping("/{clientId}")
  ClientView get(@PathVariable String clientId) {
    return service.get(clientId);
  }

  @PatchMapping("/{clientId}")
  ClientView update(
      @PathVariable String clientId, @RequestBody ClientChanges body, Authentication admin) {
    return service.update(clientId, body, actor(admin));
  }

  @PostMapping("/{clientId}/disable")
  ResponseEntity<Void> disable(@PathVariable String clientId, Authentication admin) {
    service.disable(clientId, actor(admin));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{clientId}/enable")
  ResponseEntity<Void> enable(@PathVariable String clientId, Authentication admin) {
    service.enable(clientId, actor(admin));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{clientId}/secret-rotation")
  Map<String, String> rotateSecret(@PathVariable String clientId, Authentication admin) {
    return Map.of("clientSecret", service.rotateSecret(clientId, actor(admin)));
  }

  private static long actor(Authentication admin) {
    return Long.parseLong(admin.getName());
  }
}
