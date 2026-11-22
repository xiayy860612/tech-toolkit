package tech.toolkit.auth.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code auth.*} that shape token lifetimes and the login flow. */
@ConfigurationProperties("auth")
public record AuthProperties(
    /** Issuer URL; fixed by configuration, never derived from the request. */
    @DefaultValue("http://localhost:9000") String issuer,
    @DefaultValue Tokens tokens,
    @DefaultValue Login login,
    @DefaultValue Session session,
    @DefaultValue Logout logout) {

  public record Tokens(
      /** Access token lifetime (spec: default 10 minutes, at most 15). */
      @DefaultValue("10m") Duration accessTokenTtl,
      /** Authorization code lifetime. */
      @DefaultValue("60s") Duration authorizationCodeTtl,
      /** Upper bound of a refresh token; in practice bound to the SSO session. */
      @DefaultValue("8h") Duration refreshTokenTtl) {

    public Tokens {
      if (!lifetimesAreValid(accessTokenTtl, refreshTokenTtl)) {
        throw new IllegalArgumentException(
            "the access token lifetime must be shorter than the refresh token lifetime (FR-013)");
      }
    }

    /** FR-013: an access token always lives shorter than the refresh token. */
    public static boolean lifetimesAreValid(Duration access, Duration refresh) {
      return access.compareTo(refresh) < 0;
    }
  }

  public record Session(
      /** Absolute SSO session limit, however active it is (FR-003: default 8 hours). */
      @DefaultValue("8h") Duration absoluteTimeout) {}

  public record Logout(
      /** Whether this instance delivers back-channel logout notices on a schedule. */
      @DefaultValue("true") boolean workerEnabled,
      /** Connect and response timeout of one notice (spec: 5 seconds). */
      @DefaultValue("5s") Duration httpTimeout,
      /** Waits before each retry; after the last one a notice is given up. */
      @DefaultValue({"1s", "5s", "30s", "2m", "10m"}) List<Duration> retryDelays) {}

  public record Login(
      /** How long a shown login page may be submitted (FR-002a), in seconds. */
      @DefaultValue("600") long requestTtlSeconds) {}
}
