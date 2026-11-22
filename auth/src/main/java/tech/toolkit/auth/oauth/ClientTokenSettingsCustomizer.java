package tech.toolkit.auth.oauth;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import tech.toolkit.auth.client.ClientExt;
import tech.toolkit.auth.client.ClientExtRepository;
import tech.toolkit.auth.config.AuthProperties;

/**
 * Gives each client its own token lifetimes (FR-013). The values live in {@code auth_client_ext}
 * and are read whenever a client is loaded, so a change takes effect without a restart. A pair that
 * would make the access token live as long as or longer than the refresh token is ignored and the
 * client keeps its registered lifetimes.
 */
public class ClientTokenSettingsCustomizer implements RegisteredClientRepository {

  private static final Logger log = LoggerFactory.getLogger(ClientTokenSettingsCustomizer.class);

  private final RegisteredClientRepository delegate;
  private final ClientExtRepository extensions;

  public ClientTokenSettingsCustomizer(
      RegisteredClientRepository delegate, ClientExtRepository extensions) {
    this.delegate = delegate;
    this.extensions = extensions;
  }

  @Override
  public void save(RegisteredClient registeredClient) {
    delegate.save(registeredClient);
  }

  @Override
  public RegisteredClient findById(String id) {
    return withClientLifetimes(delegate.findById(id));
  }

  @Override
  public RegisteredClient findByClientId(String clientId) {
    return withClientLifetimes(delegate.findByClientId(clientId));
  }

  private RegisteredClient withClientLifetimes(RegisteredClient client) {
    if (client == null) {
      return null;
    }
    ClientExt ext = extensions.find(client.getClientId()).orElse(null);
    if (ext == null
        || (ext.accessTokenTtlSeconds() == null && ext.refreshTokenTtlSeconds() == null)) {
      return client;
    }
    TokenSettings registered = client.getTokenSettings();
    Duration access =
        ext.accessTokenTtlSeconds() == null
            ? registered.getAccessTokenTimeToLive()
            : Duration.ofSeconds(ext.accessTokenTtlSeconds());
    Duration refresh =
        ext.refreshTokenTtlSeconds() == null
            ? registered.getRefreshTokenTimeToLive()
            : Duration.ofSeconds(ext.refreshTokenTtlSeconds());
    if (!AuthProperties.Tokens.lifetimesAreValid(access, refresh)) {
      log.warn(
          "ignoring the token lifetimes of client {}: the access token must be shorter than the refresh token",
          client.getClientId());
      return client;
    }
    return RegisteredClient.from(client)
        .tokenSettings(
            TokenSettings.withSettings(registered.getSettings())
                .accessTokenTimeToLive(access)
                .refreshTokenTimeToLive(refresh)
                .build())
        .build();
  }
}
