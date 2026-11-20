package tech.toolkit.tenantcore.platform;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 校验 BFF 签发的内部 JWT：仅接受 EdDSA（拒绝 none 与对称算法）；按 {@code kid} 选公钥（支持轮换）； 校验 iss/aud/exp（允许有限时钟偏差）与必需声明。
 */
@Component
public class InternalJwtVerifier {

  private final AdminCenterProperties.Jwt config;
  private final Clock clock;
  private final List<OctetKeyPair> keys;

  @Autowired
  public InternalJwtVerifier(AdminCenterProperties properties, Clock clock) {
    this(properties.jwt(), clock);
  }

  public InternalJwtVerifier(AdminCenterProperties.Jwt config, Clock clock) {
    this.config = config;
    this.clock = clock;
    this.keys = loadKeys(config.publicKey());
  }

  public InternalJwtClaims verify(String token) {
    if (token == null || token.isBlank()) {
      throw new InvalidInternalJwtException("missing token");
    }
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(token);
    } catch (ParseException e) {
      throw new InvalidInternalJwtException("malformed token", e);
    }
    if (!JWSAlgorithm.EdDSA.equals(jwt.getHeader().getAlgorithm())) {
      throw new InvalidInternalJwtException("unsupported algorithm");
    }
    OctetKeyPair key = selectKey(jwt.getHeader().getKeyID());
    try {
      if (!jwt.verify(new Ed25519Verifier(key))) {
        throw new InvalidInternalJwtException("bad signature");
      }
      return toClaims(jwt.getJWTClaimsSet());
    } catch (InvalidInternalJwtException e) {
      throw e;
    } catch (Exception e) {
      throw new InvalidInternalJwtException("cannot verify token", e);
    }
  }

  private OctetKeyPair selectKey(String kid) {
    if (kid == null) {
      if (keys.size() == 1) {
        return keys.get(0);
      }
      throw new InvalidInternalJwtException("missing kid");
    }
    return keys.stream()
        .filter(k -> kid.equals(k.getKeyID()))
        .findFirst()
        .orElseThrow(() -> new InvalidInternalJwtException("unknown kid"));
  }

  private InternalJwtClaims toClaims(JWTClaimsSet c) throws ParseException {
    if (!config.issuer().equals(c.getIssuer())) {
      throw new InvalidInternalJwtException("bad issuer");
    }
    if (c.getAudience() == null || !c.getAudience().contains(config.audience())) {
      throw new InvalidInternalJwtException("bad audience");
    }
    if (c.getExpirationTime() == null
        || !clock.instant().isBefore(c.getExpirationTime().toInstant().plus(config.clockSkew()))) {
      throw new InvalidInternalJwtException("expired");
    }
    if (c.getIssueTime() != null
        && c.getIssueTime().toInstant().isAfter(Instant.now(clock).plus(config.clockSkew()))) {
      throw new InvalidInternalJwtException("issued in the future");
    }
    String jti = c.getJWTID();
    if (jti == null || jti.isBlank()) {
      throw new InvalidInternalJwtException("missing jti");
    }
    Long tenant = c.getLongClaim("tenant_id");
    if (tenant == null) {
      throw new InvalidInternalJwtException("missing tenant_id");
    }
    String typ = c.getStringClaim("typ");
    String cip = c.getStringClaim("cip");
    if ("service".equals(typ)) {
      return new InternalJwtClaims(InternalJwtClaims.Type.SERVICE, jti, tenant, null, null, cip);
    }
    if (!"user".equals(typ)) {
      throw new InvalidInternalJwtException("unknown typ");
    }
    return new InternalJwtClaims(
        InternalJwtClaims.Type.USER,
        jti,
        tenant,
        userId(c.getSubject()),
        uuid(c.getStringClaim("sid"), "sid"),
        cip);
  }

  private static Long userId(String value) {
    if (value == null) {
      throw new InvalidInternalJwtException("missing sub");
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      throw new InvalidInternalJwtException("bad sub", e);
    }
  }

  private static UUID uuid(String value, String name) {
    if (value == null) {
      throw new InvalidInternalJwtException("missing " + name);
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new InvalidInternalJwtException("bad " + name, e);
    }
  }

  /** {@code public-key} 可以是 JWK、JWKS（轮换）的 JSON，或指向其文件的路径。 */
  private static List<OctetKeyPair> loadKeys(String source) {
    if (source == null || source.isBlank()) {
      throw new IllegalStateException(
          "INTERNAL_JWT_PUBLIC_KEY (admin-center.jwt.public-key) is required");
    }
    try {
      String json =
          source.trim().startsWith("{") ? source : Files.readString(Path.of(source.trim()));
      List<JWK> jwks =
          json.contains("\"keys\"") ? JWKSet.parse(json).getKeys() : List.of(JWK.parse(json));
      List<OctetKeyPair> result = new ArrayList<>();
      for (JWK jwk : jwks) {
        if (jwk instanceof OctetKeyPair okp) {
          result.add(okp.toPublicJWK());
        }
      }
      if (result.isEmpty()) {
        throw new IllegalStateException("no Ed25519 key found in INTERNAL_JWT_PUBLIC_KEY");
      }
      return result;
    } catch (IOException | ParseException e) {
      throw new IllegalStateException("cannot load INTERNAL_JWT_PUBLIC_KEY", e);
    }
  }
}
