package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.api.ClientAuthFilter;
import com.s2u2m.uam.domain.model.ClientIdentity;
import com.s2u2m.uam.domain.model.ClientRejection;
import com.s2u2m.uam.domain.model.ClientRejection.Reason;
import com.s2u2m.uam.domain.port.ClientAuthenticator;
import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T018 / T178 / T180：缺失 / 无效的服务密钥一律 401 {@code
 * client_unauthenticated}，且不透露是哪里不对；密钥有效但没有断言权限的客户端调用需要该权限的接口 → 403 {@code
 * client_forbidden}；每次拒绝都交给拒绝日志端口（原因类别、来源地址，不含密钥）。
 */
class ClientAuthFilterTest {

  private static final String VALID_KEY = "uamk_test_fake_valid_key";
  private static final String READ_ONLY_KEY = "uamk_test_fake_read_only_key";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final ClientAuthenticator authenticator =
      key ->
          VALID_KEY.equals(key)
              ? Optional.of(new ClientIdentity("admin-center", Set.of("assert-end-user-context")))
              : READ_ONLY_KEY.equals(key)
                  ? Optional.of(new ClientIdentity("reporting", Set.of()))
                  : Optional.empty();
  private final List<ClientRejection> rejections = new ArrayList<>();
  private final ClientAuthFilter filter =
      new ClientAuthFilter(authenticator, MAPPER, rejections::add);

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

  private MockHttpServletResponse callWith(
      String method, String path, String key, MockFilterChain chain) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setRemoteAddr("10.1.2.3");
    request.addHeader("Authorization", "Bearer " + key);
    return call(request, chain);
  }

  @Test
  void aClientWithoutTheAssertionPermissionIsForbiddenOnEveryNonReadCall() throws Exception {
    String[][] calls = {
      {"POST", "/v1/auth/login"},
      {"POST", "/v1/auth/sessions/verify"},
      {"POST", "/v1/auth/logout"},
      {"POST", "/v1/accounts/00000000-0000-0000-0000-000000000001/password"},
      {"POST", "/v1/users"},
      {"POST", "/v1/users/00000000-0000-0000-0000-000000000001/disable"},
      {"POST", "/v1/users/00000000-0000-0000-0000-000000000001/enable"},
      {"DELETE", "/v1/sessions/00000000-0000-0000-0000-000000000001"},
    };
    for (String[] call : calls) {
      MockFilterChain chain = new MockFilterChain();

      MockHttpServletResponse response = callWith(call[0], call[1], READ_ONLY_KEY, chain);

      assertThat(chain.getRequest()).as(call[1]).isNull();
      assertThat(response.getStatus()).as(call[1]).isEqualTo(403);
      assertThat(response.getContentType()).startsWith("application/problem+json");
      JsonNode body = MAPPER.readTree(response.getContentAsString());
      assertThat(body.get("code").asText()).isEqualTo("client_forbidden");
      assertThat(body.get("status").asInt()).isEqualTo(403);
      assertThat(response.getHeader("WWW-Authenticate")).isNull();
    }
  }

  @Test
  void aClientWithoutThePermissionStillMayRead() throws Exception {
    for (String path :
        new String[] {"/v1/users", "/v1/users/x", "/v1/online-users", "/v1/users/x/sessions"}) {
      MockFilterChain chain = new MockFilterChain();

      MockHttpServletResponse response = callWith("GET", path, READ_ONLY_KEY, chain);

      assertThat(chain.getRequest()).as(path).isNotNull();
      assertThat(response.getStatus()).isEqualTo(200);
    }
  }

  @Test
  void aClientWithThePermissionMayCallWriteEndpoints() throws Exception {
    MockFilterChain chain = new MockFilterChain();

    callWith("POST", "/v1/users", VALID_KEY, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(rejections).isEmpty();
  }

  @Test
  void everyRejectionIsLoggedWithItsReasonCategoryAndTheSourceAddress() throws Exception {
    MockHttpServletRequest missing = new MockHttpServletRequest("GET", "/v1/users");
    missing.setRemoteAddr("10.1.2.3");
    call(missing, new MockFilterChain());
    callExpectingRejection("Basic " + VALID_KEY);
    callExpectingRejection("Bearer ");
    callExpectingRejection("Bearer uamk_test_fake_wrong");
    callWith("POST", "/v1/auth/login", READ_ONLY_KEY, new MockFilterChain());

    assertThat(rejections)
        .extracting(ClientRejection::reason)
        .containsExactly(
            Reason.MISSING_KEY,
            Reason.MALFORMED,
            Reason.MALFORMED,
            Reason.UNKNOWN_KEY,
            Reason.FORBIDDEN);
    assertThat(rejections)
        .extracting(ClientRejection::status)
        .containsExactly(401, 401, 401, 401, 403);
    assertThat(rejections.get(0).sourceAddress()).isEqualTo("10.1.2.3");
    assertThat(rejections.get(0).clientId()).isNull();
    assertThat(rejections.get(4).clientId()).isEqualTo("reporting");
    assertThat(rejections.get(4).method()).isEqualTo("POST");
    assertThat(rejections.get(4).path()).isEqualTo("/v1/auth/login");
    // 拒绝记录里不可能有密钥：它根本不是这个类型的字段
    assertThat(rejections.toString()).doesNotContain("uamk_");
  }

  @Test
  void acceptedCallsAreNotLoggedAsRejections() throws Exception {
    callWith("GET", "/v1/users", VALID_KEY, new MockFilterChain());

    assertThat(rejections).isEmpty();
  }

  @Test
  void theSourceIsTheTransportPeerNotAClientSuppliedForwardedHeader() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/users");
    request.setRemoteAddr("10.1.2.3");
    request.addHeader("X-Forwarded-For", "203.0.113.9");

    call(request, new MockFilterChain());

    assertThat(rejections.get(0).sourceAddress()).isEqualTo("10.1.2.3");
  }
}
