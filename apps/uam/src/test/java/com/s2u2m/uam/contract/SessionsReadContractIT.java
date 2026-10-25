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
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * T087：{@code GET /v1/online-users} 与 {@code GET /v1/users/{userId}/sessions} 的响应按 OpenAPI
 * 校验（含「没有契约之外的字段」）。
 */
class SessionsReadContractIT extends IntegrationTestBase {

  @Value("${local.server.port}")
  int port;

  @Autowired SessionStore store;

  private ApiClient api;
  private UserFixtures data;
  private SessionFixtures sessions;
  private Instant now;
  private UUID multi;
  private Created current;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    data = new UserFixtures(jdbc);
    sessions = new SessionFixtures(store);
    now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    multi = data.user("多账号用户");
    UUID one = data.account(multi, "Multi.One", "ACTIVE", now.minusSeconds(100));
    UUID two = data.account(multi, "multi.two", "ACTIVE", now.minusSeconds(50));
    sessions.live(multi, "多账号用户", one, "Multi.One", now.minusSeconds(20));
    current = sessions.live(multi, "多账号用户", two, "multi.two", now.minusSeconds(2));
    UUID single = data.user("单账号用户");
    sessions.live(single, "单账号用户", data.account(single, "single"), "single", now.minusSeconds(60));
  }

  private void assertProblem(Response response, int status, String code) {
    assertThat(response.status()).isEqualTo(status);
    assertThat(response.contentType()).startsWith("application/problem+json");
    JsonNode body = response.json();
    assertConforms("Problem", body);
    assertThat(body.get("code").asString()).isEqualTo(code);
    assertThat(body.get("status").asInt()).isEqualTo(status);
  }

  @Test
  void listOnlineUsersConformsToOnlineUserPage() {
    Response response = api.get("/v1/online-users");

    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.json();
    assertConforms("OnlineUserPage", body);
    assertThat(body.get("items")).hasSize(2);
    assertThat(body.get("total").asLong()).isEqualTo(2);
    assertThat(body.get("items").get(0).get("sessionCount").asInt()).isEqualTo(2);
  }

  @Test
  void aPageAndAnEmptyListConformToOnlineUserPage() {
    JsonNode second = api.get("/v1/online-users?page=2&size=1").json();
    assertConforms("OnlineUserPage", second);
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("page").asInt()).isEqualTo(2);
    assertThat(second.get("size").asInt()).isEqualTo(1);

    JsonNode beyond = api.get("/v1/online-users?page=9").json();
    assertConforms("OnlineUserPage", beyond);
    assertThat(beyond.get("items")).isEmpty();
  }

  @Test
  void listUserSessionsConformsToTheSessionSchema() {
    Response response =
        api.get(
            "/v1/users/" + multi + "/sessions",
            Map.of("X-Operator-Session-Ref", current.ref().toString()));

    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.json();
    assertThat(body.get("items")).hasSize(2);
    body.get("items").forEach(item -> assertConforms("Session", item));
    assertThat(body.get("items").get(1).get("current").asBoolean()).isTrue();
    assertThat(body.propertyNames()).containsExactly("items");
  }

  @Test
  void aUserWithoutSessionsConformsWithAnEmptyItemsArray() {
    UUID idle = data.user("没有会话");

    JsonNode body = api.get("/v1/users/" + idle + "/sessions").json();

    assertThat(body.get("items")).isEmpty();
    assertThat(body.propertyNames()).containsExactly("items");
  }

  @Test
  void errorsConformToProblem() {
    assertProblem(api.get("/v1/users/" + UUID.randomUUID() + "/sessions"), 404, "user_not_found");
    assertProblem(api.get("/v1/online-users?size=101"), 422, "validation_failed");
    assertProblem(api.get("/v1/online-users?page=0"), 422, "validation_failed");
    assertProblem(new ApiClient(port, null).get("/v1/online-users"), 401, "client_unauthenticated");
    assertProblem(
        new ApiClient(port, null).get("/v1/users/" + multi + "/sessions"),
        401,
        "client_unauthenticated");
  }
}
