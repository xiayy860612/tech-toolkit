package tech.toolkit.auth.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Fixtures for local runs and tests ({@code auth.dev.seed.*}). Only read by the dev and test
 * profiles; nothing here is a default credential, every value must be configured.
 */
@ConfigurationProperties("auth.dev.seed")
public record DevSeedProperties(
    @DefaultValue("false") boolean enabled,
    String username,
    String password,
    String client1Secret,
    String client2Secret) {

  @Override
  public String toString() {
    return "DevSeedProperties[enabled=" + enabled + ", username=" + username + "]";
  }
}
