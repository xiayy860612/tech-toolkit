package tech.toolkit.tenantcore.uam;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;

/**
 * Issues and verifies Internal JWTs (docs/system-design/02-authentication.md, 4.2). Verification is
 * strict: ES256 only, our own key id, the configured issuer and audience, and not expired.
 */
public class InternalJwtService {

  /** A freshly issued Internal JWT and when it expires. */
  public record IssuedJwt(String jwt, Instant expiresAt) {
    @Override
    public String toString() {
      return "IssuedJwt[expiresAt=" + expiresAt + "]";
    }
  }

  /** The identity carried by a verified Internal JWT. */
  public record JwtPrincipal(String tenantUserId, UUID tenantId, String sid, Instant expiresAt) {}

  private final SigningKeyProvider keys;
  private final UamSettings settings;
  private final Clock clock;

  public InternalJwtService(SigningKeyProvider keys, UamSettings settings, Clock clock) {
    this.keys = keys;
    this.settings = settings;
    this.clock = clock;
  }

  public IssuedJwt issue(String tenantUserId, UUID tenantId, String sid) {
    Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = issuedAt.plusSeconds(settings.jwtTtlSeconds());
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(settings.issuer())
            .audience(settings.audience())
            .subject(tenantUserId)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .jwtID(UUID.randomUUID().toString())
            .claim("tenant_id", tenantId.toString())
            .claim("cell_id", settings.cellId())
            .claim("client_type", "web")
            .claim("azp", "platform-web")
            .claim("sid", sid)
            .build();
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.ES256)
                  .keyID(keys.keyId())
                  .type(JOSEObjectType.JWT)
                  .build(),
              claims);
      jwt.sign(new ECDSASigner(keys.key()));
      return new IssuedJwt(jwt.serialize(), expiresAt);
    } catch (Exception e) {
      throw new IllegalStateException("Could not sign Internal JWT", e);
    }
  }

  /** Verifies locally (no UAM round trip); any problem is reported as UNAUTHORIZED. */
  public JwtPrincipal verify(String token) {
    try {
      if (token == null || token.isBlank()) {
        throw invalid();
      }
      SignedJWT jwt = SignedJWT.parse(token);
      JWSHeader header = jwt.getHeader();
      if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())
          || !keys.keyId().equals(header.getKeyID())
          || !jwt.verify(new ECDSAVerifier(keys.key().toPublicJWK()))) {
        throw invalid();
      }
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      Date expiry = claims.getExpirationTime();
      if (!settings.issuer().equals(claims.getIssuer())
          || !claims.getAudience().contains(settings.audience())
          || expiry == null
          || !expiry.toInstant().isAfter(clock.instant())
          || claims.getSubject() == null
          || claims.getStringClaim("sid") == null) {
        throw invalid();
      }
      return new JwtPrincipal(
          claims.getSubject(),
          UUID.fromString(claims.getStringClaim("tenant_id")),
          claims.getStringClaim("sid"),
          expiry.toInstant());
    } catch (DomainException e) {
      throw e;
    } catch (Exception e) {
      throw invalid();
    }
  }

  private static DomainException invalid() {
    return new DomainException(ErrorCode.UNAUTHORIZED, "Invalid or expired token");
  }
}
