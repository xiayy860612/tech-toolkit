package com.s2u2m.uam.contract;

import static com.s2u2m.uam.support.OpenApiContract.assertConforms;
import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.port.SessionStore;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.SessionFixtures;
import com.s2u2m.uam.support.SessionFixtures.Created;
import com.s2u2m.uam.support.UserFixtures;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/** T126：{@code DELETE /v1/sessions/{sessionRef}} 的 204 / 400 / 404 / 409 按 OpenAPI 校验。 */
class SessionTerminateContractIT extends IntegrationTestBase {

  @Value("${local.server.port}")
  int port;

  @Autowired SessionStore store;

  private ApiClient api;
  private UUID operatorUser;
  private UUID operatorAccount;
  private Created target;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    UserFixtures data = new UserFixtures(jdbc);
    SessionFixtures sessions = new SessionFixtures(store);
    operatorUser = data.user("运维甲");
    operatorAccount = data.account(operatorUser, "ops");
    UUID targetUser = data.user("张三");
    target =
        sessions.live(
            targetUser, "张三", data.account(targetUser, "zhangsan"), "zhangsan", Instant.now());
  }

  private Map<String, String> headers(UUID operatorSessionRef) {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Operator-User-Id", operatorUser.toString());
    headers.put("X-Operator-Account-Id", operatorAccount.toString());
    headers.put("X-Operator-Session-Ref", operatorSessionRef.toString());
    return headers;
  }

  private Response delete(String ref, Map<String, String> headers) {
    return api.delete("/v1/sessions/" + ref, headers);
  }

  private void assertProblem(Response response, int status, String code) {
    assertThat(response.status()).isEqualTo(status);
    assertThat(response.contentType()).startsWith("application/problem+json");
    JsonNode problem = response.json();
    assertConforms("Problem", problem);
    assertThat(problem.get("code").asString()).isEqualTo(code);
    assertThat(problem.get("status").asInt()).isEqualTo(status);
  }

  @Test
  void terminatingAnotherSessionIs204WithNoBody() {
    Response response = delete(target.ref().toString(), headers(UUID.randomUUID()));

    assertThat(response.status()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
  }

  @Test
  void operatorRequiredConformsToProblem() {
    String ref = target.ref().toString();

    assertProblem(delete(ref, Map.of()), 400, "operator_required");

    Map<String, String> partial = headers(UUID.randomUUID());
    partial.remove("X-Operator-Session-Ref");
    assertProblem(delete(ref, partial), 400, "operator_required");

    Map<String, String> malformed = headers(UUID.randomUUID());
    malformed.put("X-Operator-User-Id", "not-a-uuid");
    assertProblem(delete(ref, malformed), 400, "operator_required");

    assertThat(redis.hasKey("uam:sref:" + ref)).as("会话没有被终止").isTrue();
  }

  @Test
  void aMalformedSessionRefIs400Problem() {
    Response response = delete("not-a-uuid", headers(UUID.randomUUID()));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.contentType()).startsWith("application/problem+json");
    assertConforms("Problem", response.json());
    assertThat(response.body()).doesNotContain("not-a-uuid");
  }

  @Test
  void anUnknownOrEndedSessionConformsToProblem404() {
    assertProblem(
        delete(UUID.randomUUID().toString(), headers(UUID.randomUUID())), 404, "session_not_found");

    delete(target.ref().toString(), headers(UUID.randomUUID()));
    assertProblem(
        delete(target.ref().toString(), headers(UUID.randomUUID())), 404, "session_not_found");
  }

  @Test
  void terminatingTheOperatorsOwnSessionConformsToProblem409() {
    assertProblem(
        delete(target.ref().toString(), headers(target.ref().value())),
        409,
        "cannot_terminate_own_session");
    assertThat(redis.hasKey("uam:sref:" + target.ref())).as("自己的会话没有被终止").isTrue();
  }

  @Test
  void aMissingServiceKeyIs401() {
    assertProblem(
        new ApiClient(port, null)
            .delete("/v1/sessions/" + target.ref(), headers(UUID.randomUUID())),
        401,
        "client_unauthenticated");
  }
}
