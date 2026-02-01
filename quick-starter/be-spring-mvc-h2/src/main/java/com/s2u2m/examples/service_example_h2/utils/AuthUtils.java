package com.s2u2m.examples.service_example_h2.utils;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthUtils {
  private final JwtEncoder jwtEncoder;

  public Jwt createJwtToken(String uid, String username, Collection<? extends GrantedAuthority> authorities) {
    Set<String> authoritySet =
      authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    JwtClaimsSet claimsSet =
      JwtClaimsSet.builder()
        .subject(uid)
        .issuedAt(Instant.now())
        .claim("scope", authoritySet)
        .claim("username", username)
        // .expiresAt(Instant.now().plusSeconds(3600))
        .build();
    return jwtEncoder.encode(JwtEncoderParameters.from(claimsSet));
  }
}
