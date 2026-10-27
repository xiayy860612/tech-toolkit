package com.s2u2m.uam.contract;

import static com.s2u2m.uam.support.OpenApiContract.assertConforms;
import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.UserFixtures;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/** T074：{@code GET /v1/users} 与 {@code GET /v1/users/{userId}} 的响应按 OpenAPI 校验（含「没有契约之外的字段」）。 */
class UsersReadContractIT extends IntegrationTestBase {

  private static final Instant BASE = Instant.parse("2026-03-01T00:00:00Z");

  @Value("${local.server.port}")
  int port;

  private ApiClient api;
  private UserFixtures data;
  private UUID multi;
  private UUID bare;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    data = new UserFixtures(jdbc);
    multi = data.user("多账号用户", "ACTIVE", BASE);
    data.account(multi, "Multi.One", "ACTIVE", BASE.plusSeconds(1));
    data.account(multi, "multi.two", "DISABLED", BASE.plusSeconds(2));
    bare = data.user("无账号用户", "DISABLED", BASE.plusSeconds(3));
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
  void listUsersConformsToUserPage() {
    Response response = api.get("/v1/users");

    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.json();
    assertConforms("UserPage", body);
    assertThat(body.get("items")).hasSize(2);
    assertThat(body.get("page").asInt()).isEqualTo(1);
    assertThat(body.get("size").asInt()).isEqualTo(20);
    assertThat(body.get("total").asLong()).isEqualTo(2);
  }

  @Test
  void aSearchResultAndAnEmptyPageConformToUserPage() {
    JsonNode found = api.get("/v1/users?query=multi&page=1&size=5").json();
    assertConforms("UserPage", found);
    assertThat(found.get("items")).hasSize(1);
    assertThat(found.get("items").get(0).get("accountCount").asInt()).isEqualTo(2);

    JsonNode empty = api.get("/v1/users?query=no-such-thing").json();
    assertConforms("UserPage", empty);
    assertThat(empty.get("items")).isEmpty();
    assertThat(empty.get("total").asLong()).isZero();
  }

  @Test
  void getUserConformsToUserDetail() {
    Response response = api.get("/v1/users/" + multi);

    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.json();
    assertConforms("UserDetail", body);
    assertThat(body.get("accounts")).hasSize(2);
  }

  @Test
  void getUserWithoutAccountsConformsToUserDetail() {
    JsonNode body = api.get("/v1/users/" + bare).json();

    assertConforms("UserDetail", body);
    assertThat(body.get("status").asString()).isEqualTo("DISABLED");
    assertThat(body.get("accounts")).isEmpty();
  }

  @Test
  void errorsConformToProblem() {
    assertProblem(api.get("/v1/users/" + UUID.randomUUID()), 404, "user_not_found");
    assertProblem(api.get("/v1/users?size=101"), 422, "validation_failed");
  }
}
