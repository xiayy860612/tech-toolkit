package tech.toolkit.auth.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;

/** Makes signed tokens for tests of the verification side. */
public final class TestTokens {

  private TestTokens() {}

  public static ECKey newKey() {
    try {
      return new ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A well-formed access token ({@code typ at+jwt}, ES256) valid for the next ten minutes. */
  public static String accessToken(ECKey key, String issuer) {
    return sign(key, "at+jwt", issuer, Instant.now().plusSeconds(600));
  }

  public static String sign(ECKey key, String typ, String issuer, Instant expiry) {
    try {
      JWSHeader header =
          new JWSHeader.Builder(JWSAlgorithm.ES256)
              .type(new JOSEObjectType(typ))
              .keyID(key.getKeyID())
              .build();
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject("1")
              .expirationTime(Date.from(expiry))
              .issueTime(new Date())
              .build();
      SignedJWT jwt = new SignedJWT(header, claims);
      jwt.sign(new ECDSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
