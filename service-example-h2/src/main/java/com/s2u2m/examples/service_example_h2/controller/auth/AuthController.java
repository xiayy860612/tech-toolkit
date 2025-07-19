package com.s2u2m.examples.service_example_h2.controller.auth;

import com.s2u2m.examples.service_example_h2.domain.user.InnerUser;
import com.s2u2m.examples.service_example_h2.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
  private final UserRepository userRepository;

  @GetMapping("/me")
  public GetUserInfoResponse getUserInfo(Authentication authentication) {
    Long uid = Long.valueOf(authentication.getName());
    InnerUser user = userRepository.getReferenceById(uid);
    return GetUserInfoResponse.builder().user(user.getUserInfo()).build();
  }
}
