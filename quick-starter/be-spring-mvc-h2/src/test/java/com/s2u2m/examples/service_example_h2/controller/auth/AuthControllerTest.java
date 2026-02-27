package com.s2u2m.examples.service_example_h2.controller.auth;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@WebMvcTest(AuthController.class)
class AuthControllerTest extends BaseControllerTest {

  @MockitoBean
  private UserRepository userRepository;
  @MockitoBean private AuthUtils authUtils;

  @Test
  @WithMockUser(username = "1")
  void shouldGetAccessToken_WhenLogin() throws Exception {
    Jwt jwt = Jwt.withTokenValue("token value").header("key", "value").subject("1").build();
    when(authUtils.createJwtToken(any(), anyString(), anyCollection())).thenReturn(jwt);

    InnerUser user = new InnerUser();
    user.setName("user");
    user.setAvatar("avatar");
    when(userRepository.getReferenceById(any())).thenReturn(user);

    mvc.perform(post("/api/auth/basic/login").contentType(MediaType.APPLICATION_JSON))
      .andExpect(status().isOk());
  }

  @Test
  @WithMockUser("1")
  void shouldGetUserInfo_AfterLogin() throws Exception {
    InnerUser user = new InnerUser();
    user.setName("user");
    user.setAvatar("avatar");
    when(userRepository.getReferenceById(any())).thenReturn(user);

    mvc.perform(get("/api/auth/me")).andExpect(status().isOk());
  }
}
