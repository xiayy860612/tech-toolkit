package com.s2u2m.uam.contract;

import static com.s2u2m.uam.support.OpenApiContract.assertConforms;
import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T145：{@code POST /v1/auth/logout} 的请求按 OpenAPI 校验；一律 204 且无响应体（会话存在、不存在、重复退出、空白 /
 * 过长的标识都一样）；不需要操作人请求头；缺服务密钥 401；退出后该会话标识的校验为 401 {@code session_invalid}，同账号以外的会话不受影响。
 */
class AuthLogoutContractIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Value("${local.server.port}")
  int port;

  @Autowired AccountProvisioningService provisioning;

  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    provisioning.createUserWithAccount("爱丽丝", "Alice", PASSWORD);
    provisioning.createUserWithAccount("鲍勃", "bob", PASSWORD);
  }

  private String login(String name) {
    Response response = api.login(name, PASSWORD, "203.0.113.7");
    assertThat(response.status()).isEqualTo(200);
    return response.json().get("sessionId").asString();
  }

  private Response logout(String sessionId) {
    Map<String, String> request = Map.of("sessionId", sessionId);
    assertConforms("VerifyRequest", JSON.valueToTree(request));
    return api.logout(sessionId);
  }

  private void assertNoContent(Response response) {
    assertThat(response.status()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
  }

  @Test
  void logoutOfALiveSessionIs204WithNoBodyAndEndsIt() {
    String sessionId = login("alice");

    Response response = logout(sessionId);

    assertNoContent(response);
    assertThat(response.body()).doesNotContain(sessionId);
    Response verify = api.verify(sessionId);
    assertThat(verify.status()).isEqualTo(401);
    assertThat(verify.contentType()).startsWith("application/problem+json");
    JsonNode problem = verify.json();
    assertConforms("Problem", problem);
    assertThat(problem.get("code").asString()).isEqualTo("session_invalid");
  }

  @Test
  void anUnknownSessionIsAlso204() {
    assertNoContent(logout("never-issued-session-id-0123456789abcdefghij"));
  }

  @Test
  void repeatingLogoutIsAlso204() {
    String sessionId = login("alice");

    assertNoContent(logout(sessionId));
    assertNoContent(logout(sessionId));
  }

  @Test
  void blankMissingOrOverlongSessionIdsAreAlso204() {
    assertNoContent(api.logout(""));
    assertNoContent(api.logout("   "));
    assertNoContent(api.logout("x".repeat(129)));
    assertNoContent(api.postJson("/v1/auth/logout", "{}"));
  }

  @Test
  void logoutNeedsNoOperatorHeadersAndOnlyEndsThatSession() {
    String alice = login("alice");
    String bob = login("bob");

    assertNoContent(logout(alice));

    assertThat(api.verify(alice).status()).isEqualTo(401);
    assertThat(api.verify(bob).status()).isEqualTo(200);
  }

  @Test
  void aMissingOrWrongServiceKeyIs401AndTheSessionSurvives() {
    String sessionId = login("alice");

    Response missing =
        new ApiClient(port, null).postJson("/v1/auth/logout", Map.of("sessionId", sessionId));
    Response wrong =
        new ApiClient(port, "uamk_wrong_key")
            .postJson("/v1/auth/logout", Map.of("sessionId", sessionId));

    for (Response response : new Response[] {missing, wrong}) {
      assertThat(response.status()).isEqualTo(401);
      assertThat(response.contentType()).startsWith("application/problem+json");
      JsonNode problem = response.json();
      assertConforms("Problem", problem);
      assertThat(problem.get("code").asString()).isEqualTo("client_unauthenticated");
    }
    assertThat(api.verify(sessionId).status()).isEqualTo(200);
  }
}
