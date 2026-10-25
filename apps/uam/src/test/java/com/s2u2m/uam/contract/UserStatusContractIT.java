package com.s2u2m.uam.contract;

import static com.s2u2m.uam.support.OpenApiContract.assertConforms;
import static com.s2u2m.uam.support.OpenApiContract.fieldNames;
import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.UserFixtures;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * T113：{@code POST /v1/users/{userId}/disable}、{@code /enable} 的 200 / 400 / 404 / 409 按 OpenAPI
 * 校验。
 */
class UserStatusContractIT extends IntegrationTestBase {

  @Value("${local.server.port}")
  int port;

  private ApiClient api;
  private UserFixtures data;
  private UUID operatorUser;
  private UUID target;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    data = new UserFixtures(jdbc);
    operatorUser = data.user("运维甲");
    target = data.user("张三");
    data.account(target, "zhangsan");
  }

  private Map<String, String> headers(UUID operator) {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Operator-User-Id", operator.toString());
    headers.put("X-Operator-Account-Id", UUID.randomUUID().toString());
    headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
    return headers;
  }

  private Response post(String action, UUID userId, Map<String, String> headers) {
    return api.postJson("/v1/users/" + userId + "/" + action, Map.of(), headers);
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
  void disableOkConformsToUserDetail() {
    Response response = post("disable", target, headers(operatorUser));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.contentType()).startsWith("application/json");
    JsonNode detail = response.json();
    assertConforms("UserDetail", detail);
    assertThat(detail.get("status").asString()).isEqualTo("DISABLED");
    assertThat(detail.get("accounts")).hasSize(1);
    assertThat(fieldNames(detail)).doesNotContain("password", "passwordHash");
  }

  @Test
  void enableOkConformsToUserDetail() {
    post("disable", target, headers(operatorUser));

    Response response = post("enable", target, headers(operatorUser));

    assertThat(response.status()).isEqualTo(200);
    JsonNode detail = response.json();
    assertConforms("UserDetail", detail);
    assertThat(detail.get("status").asString()).isEqualTo("ACTIVE");
  }

  @Test
  void repeatingEitherActionIsStill200() {
    assertThat(post("enable", target, headers(operatorUser)).status()).isEqualTo(200);
    assertThat(post("disable", target, headers(operatorUser)).status()).isEqualTo(200);
    assertThat(post("disable", target, headers(operatorUser)).status()).isEqualTo(200);
  }

  @Test
  void operatorRequiredConformsToProblemForBothActions() {
    for (String action : new String[] {"disable", "enable"}) {
      assertProblem(post(action, target, Map.of()), 400, "operator_required");

      Map<String, String> partial = headers(operatorUser);
      partial.remove("X-Operator-Session-Ref");
      assertProblem(post(action, target, partial), 400, "operator_required");

      Map<String, String> malformed = headers(operatorUser);
      malformed.put("X-Operator-Account-Id", "not-a-uuid");
      assertProblem(post(action, target, malformed), 400, "operator_required");
    }
    assertThat(
            jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, target))
        .isEqualTo("ACTIVE");
  }

  @Test
  void unknownUserConformsToProblem() {
    assertProblem(post("disable", UUID.randomUUID(), headers(operatorUser)), 404, "user_not_found");
    assertProblem(post("enable", UUID.randomUUID(), headers(operatorUser)), 404, "user_not_found");
  }

  @Test
  void disablingYourselfConformsToProblem() {
    assertProblem(post("disable", operatorUser, headers(operatorUser)), 409, "cannot_disable_self");
  }

  @Test
  void disablingTheLastActiveUserConformsToProblem() {
    post("disable", target, headers(operatorUser));

    assertProblem(
        post("disable", operatorUser, headers(UUID.randomUUID())),
        409,
        "cannot_disable_last_active_user");
  }

  @Test
  void aMissingServiceKeyIs401() {
    assertProblem(
        new ApiClient(port, null)
            .postJson("/v1/users/" + target + "/disable", Map.of(), headers(operatorUser)),
        401,
        "client_unauthenticated");
  }
}
