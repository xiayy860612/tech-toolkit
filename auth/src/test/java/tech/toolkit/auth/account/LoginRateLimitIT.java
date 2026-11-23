package tech.toolkit.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.OAuthTestBase;

/** Login attempts are limited per source address, on top of the per-account lockout. */
@TestPropertySource(
    properties = {"auth.rate-limit.login-capacity=3", "auth.rate-limit.login-refill=1h"})
class LoginRateLimitIT extends OAuthTestBase {

  private MvcResult loginFrom(String address) throws Exception {
    return mvc.perform(
            post("/login")
                .with(csrf())
                .with(
                    request -> {
                      request.setRemoteAddr(address);
                      return request;
                    })
                .param("username", USERNAME)
                .param("password", "this-is-not-the-password"))
        .andReturn();
  }

  @Test
  void aSourceIsLimitedAfterItsBurstAndToldWhenToComeBack() throws Exception {
    for (int i = 0; i < 3; i++) {
      assertThat(loginFrom("10.1.1.1").getResponse().getStatus()).isNotEqualTo(429);
    }

    MvcResult limited = loginFrom("10.1.1.1");

    assertThat(limited.getResponse().getStatus()).isEqualTo(429);
    assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isPositive();
  }

  @Test
  void anotherSourceIsNotAffected() throws Exception {
    for (int i = 0; i < 4; i++) {
      loginFrom("10.2.2.2");
    }

    assertThat(loginFrom("10.3.3.3").getResponse().getStatus()).isNotEqualTo(429);
  }

  @Test
  void onlyLoginSubmissionsCount() throws Exception {
    for (int i = 0; i < 6; i++) {
      assertThat(mvc.perform(get("/login")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
  }
}
