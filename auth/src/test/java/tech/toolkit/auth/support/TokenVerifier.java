package tech.toolkit.auth.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.util.Date;
import java.util.function.Supplier;

/**
 * What a downstream service does with an access token, offline: take the public keys from the JWKS
 * (cached, and fetched again when a token names a key it does not know, as after a rotation) and
 * check the signature, {@code typ}, {@code iss} and {@code exp} (RFC 9068 section 4).
 */
public final class TokenVerifier {

  /** The token must not be trusted. */
  public static final class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
      super(message);
    }
  }

  private final String issuer;
  private final Supplier<String> jwksFetcher;
  private final Clock clock;
  private JWKSet cached;
  private int fetches;

  public TokenVerifier(String issuer, Supplier<String> jwksFetcher, Clock clock) {
    this.issuer = issuer;
    this.jwksFetcher = jwksFetcher;
    this.clock = clock;
  }

  /** How many times the JWKS has been fetched so far. */
  public int fetches() {
    return fetches;
  }

  /** The claims of a valid token; throws {@link InvalidTokenException} for any other. */
  public JWTClaimsSet verify(String token) {
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(token);
    } catch (ParseException e) {
      throw new InvalidTokenException("not a signed JWT");
    }
    if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
      throw new InvalidTokenException("only ES256 is accepted");
    }
    if (jwt.getHeader().getType() == null
        || !"at+jwt".equals(jwt.getHeader().getType().getType())) {
      throw new InvalidTokenException("typ must be at+jwt");
    }
    ECKey key = keyFor(jwt.getHeader().getKeyID());
    try {
      if (!jwt.verify(new ECDSAVerifier(key.toECPublicKey()))) {
        throw new InvalidTokenException("bad signature");
      }
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      if (!issuer.equals(claims.getIssuer())) {
        throw new InvalidTokenException("wrong issuer");
      }
      Date expiry = claims.getExpirationTime();
      if (expiry == null || !expiry.toInstant().isAfter(clock.instant())) {
        throw new InvalidTokenException("expired");
      }
      return claims;
    } catch (JOSEException | ParseException e) {
      throw new InvalidTokenException("cannot verify: " + e.getMessage());
    }
  }

  private ECKey keyFor(String keyId) {
    if (keyId == null) {
      throw new InvalidTokenException("no kid");
    }
    JWK key = cached == null ? null : cached.getKeyByKeyId(keyId);
    if (key == null) {
      // an unknown kid: the keys may have been rotated, so look again once
      refresh();
      key = cached.getKeyByKeyId(keyId);
    }
    if (key instanceof ECKey ec) {
      return ec;
    }
    throw new InvalidTokenException("unknown key " + keyId);
  }

  private void refresh() {
    fetches++;
    try {
      cached = JWKSet.parse(jwksFetcher.get());
    } catch (ParseException e) {
      throw new InvalidTokenException("the JWKS is not valid");
    }
  }
}
