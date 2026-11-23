package tech.toolkit.auth.bootstrap;

import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.auth.account.AccountSecurityService;
import tech.toolkit.auth.account.UserRepository;
import tech.toolkit.auth.admin.AdminScopeCustomizer;
import tech.toolkit.auth.audit.AuditEvent;
import tech.toolkit.auth.audit.AuditService;
import tech.toolkit.auth.audit.AuditType;
import tech.toolkit.auth.config.AuthProperties;

/**
 * The one-time setup of a new deployment (FR-019a): the first administrator, who must change the
 * password at the first login, and the public client {@code auth-admin} that the admin tools log in
 * with. Safe to run again: whatever exists is left exactly as it is. It does not depend on the dev
 * seed, so a production deployment has the client too.
 */
@Service
public class BootstrapService {

  public static final String ADMIN_CLIENT_ID = "auth-admin";

  /** What this run created. */
  public record Result(boolean adminCreated, boolean clientRegistered) {}

  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final AccountSecurityService accounts;
  private final RegisteredClientRepository clients;
  private final AuditService audit;
  private final AuthProperties properties;

  public BootstrapService(
      UserRepository users,
      PasswordEncoder encoder,
      AccountSecurityService accounts,
      RegisteredClientRepository clients,
      AuditService audit,
      AuthProperties properties) {
    this.users = users;
    this.encoder = encoder;
    this.accounts = accounts;
    this.clients = clients;
    this.audit = audit;
    this.properties = properties;
  }

  @Transactional
  public Result run(String username, String password, List<String> adminRedirectUris) {
    if (adminRedirectUris == null || adminRedirectUris.isEmpty()) {
      throw new IllegalArgumentException(
          "AUTH_ADMIN_REDIRECT_URIS needs at least one loopback redirect address");
    }
    return new Result(
        createAdministrator(username, password), registerAdminClient(adminRedirectUris));
  }

  private boolean createAdministrator(String username, String password) {
    String name = UserRepository.normalize(username);
    if (!UserRepository.isValidUsername(name)) {
      throw new IllegalArgumentException("the administrator's username is not valid");
    }
    if (users.findByUsername(name).isPresent()) {
      return false;
    }
    List<String> problems = accounts.policyProblems(password, name);
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(String.join("；", problems));
    }
    long id = users.insert(name, encoder.encode(password), true, true);
    audit.record(AuditEvent.of(AuditType.USER_CREATED, true).actor("bootstrap").subjectUserId(id));
    return true;
  }

  private boolean registerAdminClient(List<String> redirectUris) {
    if (clients.findByClientId(ADMIN_CLIENT_ID) != null) {
      return false;
    }
    AuthProperties.Tokens tokens = properties.tokens();
    RegisteredClient.Builder client =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(ADMIN_CLIENT_ID)
            .clientName("auth admin tools")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .scope(OidcScopes.OPENID)
            .scope(AdminScopeCustomizer.ADMIN_SCOPE)
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(false)
                    .build())
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenTimeToLive(tokens.accessTokenTtl())
                    .authorizationCodeTimeToLive(tokens.authorizationCodeTtl())
                    .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                    .build());
    redirectUris.forEach(client::redirectUri);
    clients.save(client.build());
    return true;
  }
}
