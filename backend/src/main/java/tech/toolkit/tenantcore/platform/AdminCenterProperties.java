package tech.toolkit.tenantcore.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admin-center")
public record AdminCenterProperties(Login login, Session session, Jwt jwt, Password password) {

  public record Login(int maxFailedAttempts, Duration lockDuration) {}

  public record Session(
      Duration idleTimeout, Duration absoluteTimeout, Duration retention, Duration touchInterval) {}

  public record Jwt(String issuer, String audience, String publicKey, Duration clockSkew) {}

  public record Password(int memoryKib, int iterations, int parallelism) {}
}
