package tech.toolkit.auth.oauth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the signing keys to the token encoder and the JWKS endpoint. Only the current key is
 * private, so only it can sign; verify-only keys are public-only and still get published.
 */
@Configuration
public class JwkSourceConfig {

  @Bean
  public JWKSource<SecurityContext> jwkSource(SigningKeyProvider keys) {
    List<JWK> all = new ArrayList<>(keys.all());
    return new ImmutableJWKSet<>(new JWKSet(all));
  }
}
