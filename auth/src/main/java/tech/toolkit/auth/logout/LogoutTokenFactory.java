package tech.toolkit.auth.logout;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.toolkit.auth.config.AuthProperties;
import tech.toolkit.auth.oauth.SigningKeyProvider;

/**
 * Builds the signed {@code logout_token} of OpenID Connect Back-Channel Logout 1.0: issuer, the
 * client as audience, the login's {@code sid}, and the back-channel logout event. It carries no
 * {@code nonce}, as the specification requires. Every delivery attempt gets a fresh token.
 */
@Component
public class LogoutTokenFactory {

  static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";

  private final SigningKeyProvider keys;
  private final Clock clock;
  private final String issuer;

  public LogoutTokenFactory(SigningKeyProvider keys, Clock clock, AuthProperties properties) {
    this.keys = keys;
    this.clock = clock;
    this.issuer = properties.issuer();
  }

  public String create(String clientId, String sid) {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(List.of(clientId))
            .issueTime(Date.from(clock.instant()))
            .jwtID(UUID.randomUUID().toString())
            .claim("sid", sid)
            .claim("events", Map.of(EVENT, Map.of()))
            .build();
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.ES256)
            .keyID(keys.current().getKeyID())
            .type(new JOSEObjectType("logout+jwt"))
            .build();
    try {
      SignedJWT token = new SignedJWT(header, claims);
      token.sign(new ECDSASigner(keys.current()));
      return token.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("cannot sign the logout token", e);
    }
  }
}
