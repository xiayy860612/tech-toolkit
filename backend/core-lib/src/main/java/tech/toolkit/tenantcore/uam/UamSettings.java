package tech.toolkit.tenantcore.uam;

import java.time.Duration;
import java.util.Map;

/**
 * UAM configuration. {@code signingKeyPem} is an ES256 private key (PKCS#8 PEM) that must come from
 * the environment or a mounted secret; only the dev profile may fall back to an ephemeral key.
 */
public record UamSettings(
    String issuer,
    String audience,
    String cellId,
    int jwtTtlSeconds,
    String signingKeyPem,
    boolean allowEphemeralKey) {

  /** A Session ends after 30 minutes without use... */
  public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

  /** ...and in any case 8 hours after login. */
  public static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(8);

  /** Defaults for tests, the Bootstrap CLI (which never signs) and local development. */
  public static UamSettings ephemeral() {
    return new UamSettings(
        "https://uam.platform.example.internal",
        "platform-services",
        "platform-1",
        120,
        null,
        true);
  }

  public static UamSettings fromEnv(Map<String, String> env, boolean dev) {
    UamSettings defaults = ephemeral();
    return new UamSettings(
        env.getOrDefault("UAM_ISSUER", defaults.issuer()),
        env.getOrDefault("UAM_AUDIENCE", defaults.audience()),
        env.getOrDefault("UAM_CELL_ID", defaults.cellId()),
        Integer.parseInt(env.getOrDefault("UAM_JWT_TTL_SECONDS", "120")),
        env.get("UAM_SIGNING_KEY_PEM"),
        dev);
  }

  public UamSettings withIssuer(String newIssuer) {
    return new UamSettings(
        newIssuer, audience, cellId, jwtTtlSeconds, signingKeyPem, allowEphemeralKey);
  }

  public UamSettings withAudience(String newAudience) {
    return new UamSettings(
        issuer, newAudience, cellId, jwtTtlSeconds, signingKeyPem, allowEphemeralKey);
  }

  public UamSettings withSigningKeyPem(String pem, boolean allowEphemeral) {
    return new UamSettings(issuer, audience, cellId, jwtTtlSeconds, pem, allowEphemeral);
  }

  @Override
  public String toString() {
    return "UamSettings[issuer="
        + issuer
        + ", audience="
        + audience
        + ", cellId="
        + cellId
        + ", jwtTtlSeconds="
        + jwtTtlSeconds
        + ", signingKey="
        + (signingKeyPem == null || signingKeyPem.isBlank() ? "none" : "configured")
        + "]";
  }
}
