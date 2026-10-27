package com.s2u2m.uam.contract;

import static com.s2u2m.uam.support.OpenApiContract.assertConforms;
import static com.s2u2m.uam.support.OpenApiContract.fieldNames;
import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T099：{@code POST /v1/users} 的 201 / 400 {@code operator_required} / 409 / 422 按 OpenAPI 校验。 */
class UsersWriteContractIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final String KEY = "0123456789abcdef-key-1";

  @Value("${local.server.port}")
  int port;

  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
  }

  private Map<String, String> headers(String key) {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Operator-User-Id", UUID.randomUUID().toString());
    headers.put("X-Operator-Account-Id", UUID.randomUUID().toString());
    headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
    if (key != null) {
      headers.put("Idempotency-Key", key);
    }
    return headers;
  }

  private Map<String, Object> body(String accountName, String password) {
    return Map.of("userName", "张三", "accountName", accountName, "password", password);
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
  void createdConformsToUserDetail() {
    Map<String, Object> request = body("zhangsan", PASSWORD);
    assertConforms("CreateUserRequest", JsonMapper.builder().build().valueToTree(request));

    Response response = api.postJson("/v1/users", request, headers(KEY));

    assertThat(response.status()).isEqualTo(201);
    assertThat(response.contentType()).startsWith("application/json");
    JsonNode created = response.json();
    assertConforms("UserDetail", created);
    assertThat(created.get("accounts")).hasSize(1);
    assertThat(fieldNames(created)).doesNotContain("password", "passwordHash");
  }

  @Test
  void operatorRequiredConformsToProblem() {
    Map<String, String> noOperator = new HashMap<>(Map.of("Idempotency-Key", KEY));
    assertProblem(
        api.postJson("/v1/users", body("zhangsan", PASSWORD), noOperator),
        400,
        "operator_required");

    Map<String, String> partial = headers(KEY);
    partial.remove("X-Operator-Session-Ref");
    assertProblem(
        api.postJson("/v1/users", body("zhangsan", PASSWORD), partial), 400, "operator_required");

    Map<String, String> malformed = headers(KEY);
    malformed.put("X-Operator-User-Id", "not-a-uuid");
    assertProblem(
        api.postJson("/v1/users", body("zhangsan", PASSWORD), malformed), 400, "operator_required");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class)).isZero();
  }

  @Test
  void conflictConformsToProblem() {
    api.postJson("/v1/users", body("zhangsan", PASSWORD), headers(KEY));

    Response response =
        api.postJson("/v1/users", body("ZHANGSAN", PASSWORD), headers("other-key-0123456789"));

    assertProblem(response, 409, "account_name_taken");
  }

  @Test
  void validationFailedConformsToProblemWithFieldErrors() {
    Response response =
        api.postJson(
            "/v1/users",
            Map.of("userName", " ", "accountName", "a!", "password", "short"),
            headers(KEY));

    assertProblem(response, 422, "validation_failed");
    JsonNode errors = response.json().get("errors");
    assertThat(errors).hasSize(3);
    assertThat(errors.get(0).get("field").asString()).isEqualTo("userName");
    assertThat(errors.get(1).get("field").asString()).isEqualTo("accountName");
    assertThat(errors.get(2).get("field").asString()).isEqualTo("password");
    assertThat(response.body()).doesNotContain("\"short\"");
  }

  @Test
  void aUserNameWithNulIsInvalidCharsNotAServerError() {
    Response response =
        api.postJson(
            "/v1/users",
            Map.of("userName", "a\u0000b", "accountName", "zhangsan", "password", PASSWORD),
            headers(KEY));

    assertProblem(response, 422, "validation_failed");
    JsonNode errors = response.json().get("errors");
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).get("field").asString()).isEqualTo("userName");
    assertThat(errors.get(0).get("reason").asString()).isEqualTo("invalid_chars");
  }

  @Test
  void missingFieldsAndMissingIdempotencyKeyAre422() {
    assertProblem(api.postJson("/v1/users", Map.of(), headers(KEY)), 422, "validation_failed");
    assertProblem(
        api.postJson("/v1/users", body("zhangsan", PASSWORD), headers(null)),
        422,
        "validation_failed");
  }
}
