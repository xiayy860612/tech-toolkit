package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.api.ClientAuthFilter;
import com.s2u2m.uam.domain.model.ClientIdentity;
import com.s2u2m.uam.domain.port.ClientAuthenticator;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T018：缺失 / 无效的服务密钥一律 401 {@code client_unauthenticated}，且不透露是哪里不对。 */
class ClientAuthFilterTest {

  private static final String VALID_KEY = "uamk_test_fake_valid_key";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final ClientAuthenticator authenticator =
      key ->
          VALID_KEY.equals(key)
              ? Optional.of(new ClientIdentity("admin-center", Set.of("assert-end-user-context")))
              : Optional.empty();
  private final ClientAuthFilter filter = new ClientAuthFilter(authenticator, MAPPER);

  private MockHttpServletResponse call(MockHttpServletRequest request, FilterChain chain)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private MockHttpServletResponse callExpectingRejection(String authorization) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/users");
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = call(request, chain);
    assertThat(chain.getRequest()).as("请求不得继续向下传递").isNull();
    return response;
  }

  @Test
  void validKeyPassesAndExposesClientIdAsRequestAttribute() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/users");
    request.addHeader("Authorization", "Bearer " + VALID_KEY);
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response = call(request, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(request.getAttribute(ClientAuthFilter.CLIENT_ID_ATTRIBUTE))
        .isEqualTo("admin-center");
  }

  @Test
  void schemeIsCaseInsensitive() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login");
    request.addHeader("Authorization", "bearer " + VALID_KEY);
    MockFilterChain chain = new MockFilterChain();

    call(request, chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void missingInvalidAndMalformedCredentialsAreAllRejectedIdentically() throws Exception {
    MockHttpServletResponse missing = callExpectingRejection(null);
    MockHttpServletResponse wrongKey = callExpectingRejection("Bearer uamk_test_fake_wrong");
    MockHttpServletResponse wrongScheme = callExpectingRejection("Basic " + VALID_KEY);
    MockHttpServletResponse emptyToken = callExpectingRejection("Bearer ");
    MockHttpServletResponse noToken = callExpectingRejection("Bearer");

    for (MockHttpServletResponse response :
        new MockHttpServletResponse[] {missing, wrongKey, wrongScheme, emptyToken, noToken}) {
      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getContentType()).startsWith("application/problem+json");
      JsonNode body = MAPPER.readTree(response.getContentAsString());
      assertThat(body.get("code").asText()).isEqualTo("client_unauthenticated");
      assertThat(body.get("status").asInt()).isEqualTo(401);
    }
    // 响应不透露是哪一部分不对：所有拒绝的响应体与头完全一致
    assertThat(wrongKey.getContentAsString()).isEqualTo(missing.getContentAsString());
    assertThat(wrongScheme.getContentAsString()).isEqualTo(missing.getContentAsString());
    assertThat(emptyToken.getContentAsString()).isEqualTo(missing.getContentAsString());
    assertThat(wrongKey.getHeader("WWW-Authenticate"))
        .isEqualTo(missing.getHeader("WWW-Authenticate"));
  }

  @Test
  void rejectionNeverEchoesTheSubmittedKey() throws Exception {
    MockHttpServletResponse response = callExpectingRejection("Bearer uamk_test_fake_wrong");

    assertThat(response.getContentAsString()).doesNotContain("uamk_test_fake_wrong");
    assertThat(response.getHeaderNames())
        .allSatisfy(name -> assertThat(response.getHeader(name)).doesNotContain("uamk_"));
  }

  @Test
  void healthProbesAreNotSubjectToServiceKeyChecks() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/actuator/health/readiness");
    request.setServletPath("/actuator/health/readiness");
    MockFilterChain chain = new MockFilterChain();

    call(request, chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void otherActuatorEndpointsStillRequireTheKey() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
    request.setServletPath("/actuator/prometheus");
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response = call(request, chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(response.getStatus()).isEqualTo(401);
  }
}
