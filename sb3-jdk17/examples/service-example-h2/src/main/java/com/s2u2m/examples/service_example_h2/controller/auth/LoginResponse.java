package com.s2u2m.examples.service_example_h2.controller.auth;

import com.s2u2m.examples.service_example_h2.domain.user.UserInfo;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
  private String accessToken;
  private UserInfo user;
}
