package tech.toolkit.tenantcore.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** 测试用：模拟 BFF 签发内部 JWT。 */
public final class TestJwt {

  public static final String ISSUER = "admin-center-bff";
  public static final String AUDIENCE = "tenant-core-api";

  private String issuer = ISSUER;
  private String audience = AUDIENCE;
  private Instant issuedAt = Instant.now();
  private Instant expiresAt = issuedAt.plus(Duration.ofSeconds(60));
  private final JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
  private OctetKeyPair key = TestKeys.keyPair();
  private String kid = "test-key";

  private TestJwt(String typ, long tenantId) {
    claims.jwtID(UUID.randomUUID().toString()).claim("typ", typ).claim("tenant_id", tenantId);
    claims.claim("cip", "203.0.113.5");
  }

  public static TestJwt user(Long userId, UUID sessionId, long tenantId) {
    TestJwt t = new TestJwt("user", tenantId);
    t.claims.subject(userId.toString()).claim("sid", sessionId.toString());
    return t;
  }

  public static TestJwt service(long tenantId) {
    return new TestJwt("service", tenantId);
  }

  public TestJwt issuer(String v) {
    this.issuer = v;
    return this;
  }

  public TestJwt audience(String v) {
    this.audience = v;
    return this;
  }

  /** 以指定时刻为签发时间（60 秒有效），用于配合可前进的测试时钟。 */
  public TestJwt at(Instant now) {
    this.issuedAt = now;
    this.expiresAt = now.plus(Duration.ofSeconds(60));
    return this;
  }

  public TestJwt expiresAt(Instant v) {
    this.expiresAt = v;
    return this;
  }

  public TestJwt without(String claim) {
    claims.claim(claim, null);
    return this;
  }

  public TestJwt signedWith(OctetKeyPair k, String keyId) {
    this.key = k;
    this.kid = keyId;
    return this;
  }

  private JWTClaimsSet claimsSet() {
    return claims
        .issuer(issuer)
        .audience(audience)
        .issueTime(Date.from(issuedAt))
        .expirationTime(Date.from(expiresAt))
        .build();
  }

  public String sign() {
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(kid).type(JOSEObjectType.JWT).build(),
              claimsSet());
      JWSSigner signer = new Ed25519Signer(key);
      jwt.sign(signer);
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** alg=none 的无签名令牌（必须被拒绝）。 */
  public String unsigned() {
    return new PlainJWT(claimsSet()).serialize();
  }

  /** 对称算法 HS256 令牌（必须被拒绝）。 */
  public String hs256() {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet());
      jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
