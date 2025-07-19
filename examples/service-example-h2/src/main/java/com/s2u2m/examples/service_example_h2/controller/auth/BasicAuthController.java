package com.s2u2m.examples.service_example_h2.controller.auth;

import com.s2u2m.examples.service_example_h2.domain.user.InnerUser;
import com.s2u2m.examples.service_example_h2.domain.user.UserRepository;
import com.s2u2m.examples.service_example_h2.utils.AuthUtils;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/basic")
@RequiredArgsConstructor
public class BasicAuthController {
  private final AuthUtils authUtils;
  private final UserRepository userRepository;

  @PostMapping("/login")
  public LoginResponse loginSuccess(Authentication authentication) throws IOException {
    Long uid = Long.valueOf(authentication.getName());
    InnerUser user = userRepository.getReferenceById(uid);
    Jwt token = authUtils.createJwtToken(authentication.getName(), authentication.getAuthorities());
    return LoginResponse.builder()
        .accessToken(token.getTokenValue())
        .user(user.getUserInfo())
        .build();
  }
}
