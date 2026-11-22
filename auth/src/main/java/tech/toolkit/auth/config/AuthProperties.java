package tech.toolkit.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code auth.*} that shape token lifetimes and the login flow. */
@ConfigurationProperties("auth")
public record AuthProperties(
    /** Issuer URL; fixed by configuration, never derived from the request. */
    @DefaultValue("http://localhost:9000") String issuer,
    @DefaultValue Tokens tokens,
    @DefaultValue Login login) {

  public record Tokens(
      /** Access token lifetime (spec: default 10 minutes, at most 15). */
      @DefaultValue("10m") Duration accessTokenTtl,
      /** Authorization code lifetime. */
      @DefaultValue("60s") Duration authorizationCodeTtl,
      /** Upper bound of a refresh token; in practice bound to the SSO session. */
      @DefaultValue("8h") Duration refreshTokenTtl) {}

  public record Login(
      /** How long a shown login page may be submitted (FR-002a), in seconds. */
      @DefaultValue("600") long requestTtlSeconds) {}
}
