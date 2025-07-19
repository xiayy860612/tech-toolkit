package com.s2u2m.examples.service_example_h2.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class GlobalSecurityConfig {

  @Bean
  public SecurityFilterChain globalFilterChain(HttpSecurity http) throws Exception {
    return new GlobalHttpSecurityCustomizer().customize(http).build();
  }

  @Bean
  public JwtEncoder jwtEncoder(
      @Value("${s2u2m.security.jwt.publicKey}") RSAPublicKey publicKey,
      @Value("${s2u2m.security.jwt.privateKey}") RSAPrivateKey privateKey) {
    RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
    JWKSet jwkSet = new JWKSet(rsaKey);
    return new NimbusJwtEncoder((selector, context) -> selector.select(jwkSet));
  }
}
