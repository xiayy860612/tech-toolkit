package com.s2u2m.examples.service_example_h2.controller.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.s2u2m.examples.service_example_h2.BaseControllerTest;
import com.s2u2m.examples.service_example_h2.domain.user.InnerUser;
import com.s2u2m.examples.service_example_h2.domain.user.UserRepository;
import com.s2u2m.examples.service_example_h2.utils.AuthUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@WebMvcTest(BasicAuthController.class)
class BasicAuthControllerTest extends BaseControllerTest {

  @MockitoBean private AuthUtils authUtils;
  @MockitoBean private UserRepository userRepository;

  @Test
  @WithMockUser(username = "1")
  void shouldGetAccessToken_WhenLoginSuccess() throws Exception {
    Jwt jwt = Jwt.withTokenValue("token value").header("key", "value").subject("1").build();
    when(authUtils.createJwtToken(any(), anyCollection())).thenReturn(jwt);

    InnerUser user = new InnerUser();
    user.setName("user");
    user.setAvatar("avatar");
    when(userRepository.getReferenceById(any())).thenReturn(user);

    mvc.perform(post("/api/auth/basic/login").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }
}
