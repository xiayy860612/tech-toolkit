package tech.toolkit.auth.client;

import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import tech.toolkit.auth.account.UserRepository;
import tech.toolkit.auth.config.AuthProperties;

/**
 * Registers the example user and the example clients client1 and client2 for local runs and tests.
 * Never active in production: it needs the dev or test profile and configured values.
 */
@Component
@Profile({"dev", "test"})
public class DevClientSeeder implements ApplicationRunner {

  private final DevSeedProperties seed;
  private final AuthProperties properties;
  private final UserRepository users;
  private final RegisteredClientRepository clients;
  private final PasswordEncoder encoder;
  private final ClientExtRepository clientExt;

  public DevClientSeeder(
      DevSeedProperties seed,
      AuthProperties properties,
      UserRepository users,
      RegisteredClientRepository clients,
      PasswordEncoder encoder,
      ClientExtRepository clientExt) {
    this.seed = seed;
    this.properties = properties;
    this.users = users;
    this.clients = clients;
    this.encoder = encoder;
    this.clientExt = clientExt;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (seed.enabled()) {
      seed();
      // the dev receiver logs the notice instead of ending a real client session
      String receiver = properties.issuer() + "/dev/backchannel-logout";
      clientExt.upsert(new ClientExt("client1", true, receiver, null, null, null));
      clientExt.upsert(new ClientExt("client2", true, receiver, null, null, null));
    }
  }

  /** Idempotent: creates whatever is missing. */
  public void seed() {
    if (users.findByUsername(seed.username()).isEmpty()) {
      users.insert(seed.username(), encoder.encode(seed.password()), false, false);
    }
    register("client1", seed.client1Secret(), "http://127.0.0.1:8081/callback");
    register("client2", seed.client2Secret(), "http://127.0.0.1:8082/callback");
  }

  private void register(String clientId, String secret, String redirectUri) {
    if (clients.findByClientId(clientId) != null) {
      return;
    }
    AuthProperties.Tokens tokens = properties.tokens();
    clients.save(
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(clientId)
            .clientSecret(encoder.encode(secret))
            .clientName(clientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri(redirectUri)
            .postLogoutRedirectUri(redirectUri.replace("/callback", "/logged-out"))
            .scope(OidcScopes.OPENID)
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(false)
                    .build())
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenTimeToLive(tokens.accessTokenTtl())
                    .authorizationCodeTimeToLive(tokens.authorizationCodeTtl())
                    .refreshTokenTimeToLive(tokens.refreshTokenTtl())
                    .reuseRefreshTokens(false)
                    .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                    .build())
            .build());
  }
}
