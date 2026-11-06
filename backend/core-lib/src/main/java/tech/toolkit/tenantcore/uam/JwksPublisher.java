package tech.toolkit.tenantcore.uam;

import com.nimbusds.jose.jwk.JWKSet;
import java.util.Map;

/** Publishes the public half of the signing key as a JWKS document (never the private part). */
public class JwksPublisher {

  private final SigningKeyProvider keys;

  public JwksPublisher(SigningKeyProvider keys) {
    this.keys = keys;
  }

  public Map<String, Object> jwks() {
    return new JWKSet(keys.key().toPublicJWK()).toJSONObject();
  }
}
