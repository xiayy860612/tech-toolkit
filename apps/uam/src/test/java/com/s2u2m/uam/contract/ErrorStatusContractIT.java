package com.s2u2m.uam.contract;

import static com.s2u2m.uam.support.OpenApiContract.assertConforms;
import static com.s2u2m.uam.support.OpenApiContract.declaredStatuses;
import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.OpenApiContract;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * 状态码统一（一个错误码对应一个状态码）：{@code validation_failed} 一律 422，读不懂的请求一律 400 {@code bad_request}，{@code
 * operator_required} 仍为 400，已知格式但不存在的 id 为 404。
 *
 * <p>遍历契约的每个接口：每个接口至少有一条用例；每条用例的状态码必须在契约里声明；错误体符合 {@code Problem}，不回显请求里的任何值。
 */
class ErrorStatusContractIT extends IntegrationTestBase {

  private static final String JUNK = "junk-hunter2-value";
  private static final String KEY = "0123456789abcdef-key-1";
  private static final String IP = "203.0.113.7";

  /** 一条用例：契约接口、描述、发请求的动作、期望的状态码与原因码（{@code null} 表示无响应体）。 */
  record Case(
      String operationId,
      String label,
      Function<ErrorStatusContractIT, Response> call,
      int status,
      String code) {
    @Override
    public String toString() {
      return operationId + " · " + label + " → " + status + (code == null ? "" : " " + code);
    }
  }

  @Value("${local.server.port}")
  int port;

  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
  }

  private static Case c(
      String id, String label, Function<ErrorStatusContractIT, Response> call, int s, String code) {
    return new Case(id, label, call, s, code);
  }

  private Map<String, String> operator() {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Operator-User-Id", UUID.randomUUID().toString());
    headers.put("X-Operator-Account-Id", UUID.randomUUID().toString());
    headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
    return headers;
  }

  private Map<String, String> operatorWithKey() {
    Map<String, String> headers = operator();
    headers.put("Idempotency-Key", KEY);
    return headers;
  }

  private Response login(Object body) {
    return api.postJson("/v1/auth/login", body);
  }

  static List<Case> cases() {
    String random = UUID.randomUUID().toString();
    return List.of(
        // ---- login ----
        c(
            "login",
            "缺 accountName",
            t -> t.login(Map.of("password", "x", "clientIp", IP)),
            422,
            "validation_failed"),
        c(
            "login",
            "password 为空",
            t -> t.login(Map.of("accountName", "alice", "password", "", "clientIp", IP)),
            422,
            "validation_failed"),
        c(
            "login",
            "缺 clientIp",
            t -> t.login(Map.of("accountName", "alice", "password", "x")),
            422,
            "validation_failed"),
        c(
            "login",
            "accountName 过长",
            t -> t.login(Map.of("accountName", "a".repeat(65), "password", "x", "clientIp", IP)),
            422,
            "validation_failed"),
        c("login", "坏 JSON", t -> t.login("{not json " + JUNK), 400, "bad_request"),
        c("login", "空请求体", t -> t.login(""), 400, "bad_request"),
        c(
            "login",
            "JSON 类型不对",
            t -> t.login("{\"accountName\":{\"x\":\"" + JUNK + "\"},\"password\":\"x\"}"),
            400,
            "bad_request"),
        // ---- verify / logout ----
        c(
            "verifySession",
            "坏 JSON",
            t -> t.api.postJson("/v1/auth/sessions/verify", "{not json " + JUNK),
            400,
            "bad_request"),
        c("verifySession", "sessionId 为空仍是 401", t -> t.api.verify(""), 401, "session_invalid"),
        c(
            "verifySession",
            "sessionId 过长仍是 401",
            t -> t.api.verify("s".repeat(500)),
            401,
            "session_invalid"),
        c(
            "logout",
            "坏 JSON",
            t -> t.api.postJson("/v1/auth/logout", "{not json " + JUNK),
            400,
            "bad_request"),
        c("logout", "sessionId 为空仍是 204", t -> t.api.logout(""), 204, null),
        // ---- users ----
        c("listUsers", "page=abc", t -> t.api.get("/v1/users?page=" + JUNK), 400, "bad_request"),
        c("listUsers", "size=x", t -> t.api.get("/v1/users?size=" + JUNK), 400, "bad_request"),
        c("listUsers", "page=0", t -> t.api.get("/v1/users?page=0"), 422, "validation_failed"),
        c("listUsers", "size=101", t -> t.api.get("/v1/users?size=101"), 422, "validation_failed"),
        c(
            "listUsers",
            "query 过长",
            t -> t.api.get("/v1/users?query=" + "q".repeat(65)),
            422,
            "validation_failed"),
        c(
            "createUser",
            "空对象",
            t -> t.api.postJson("/v1/users", Map.of(), t.operatorWithKey()),
            422,
            "validation_failed"),
        c(
            "createUser",
            "缺 Idempotency-Key",
            t ->
                t.api.postJson(
                    "/v1/users",
                    Map.of("userName", "张三", "accountName", "zhangsan", "password", "x"),
                    t.operator()),
            422,
            "validation_failed"),
        c(
            "createUser",
            "坏 JSON",
            t -> t.api.postJson("/v1/users", "{not json " + JUNK, t.operatorWithKey()),
            400,
            "bad_request"),
        c(
            "createUser",
            "空请求体",
            t -> t.api.postJson("/v1/users", "", t.operatorWithKey()),
            400,
            "bad_request"),
        c(
            "createUser",
            "JSON 类型不对",
            t ->
                t.api.postJson(
                    "/v1/users", "{\"userName\":{\"x\":\"" + JUNK + "\"}}", t.operatorWithKey()),
            400,
            "bad_request"),
        c(
            "createUser",
            "缺操作人头仍是 operator_required",
            t ->
                t.api.postJson(
                    "/v1/users",
                    Map.of("userName", "张三", "accountName", "zhangsan", "password", "x"),
                    Map.of("Idempotency-Key", KEY)),
            400,
            "operator_required"),
        c("getUser", "非 UUID", t -> t.api.get("/v1/users/" + JUNK), 400, "bad_request"),
        c("getUser", "不存在的 id", t -> t.api.get("/v1/users/" + random), 404, "user_not_found"),
        c(
            "disableUser",
            "非 UUID",
            t -> t.api.postJson("/v1/users/" + JUNK + "/disable", Map.of(), t.operator()),
            400,
            "bad_request"),
        c(
            "disableUser",
            "缺操作人头仍是 operator_required",
            t -> t.api.postJson("/v1/users/" + random + "/disable", Map.of(), Map.of()),
            400,
            "operator_required"),
        c(
            "enableUser",
            "非 UUID",
            t -> t.api.postJson("/v1/users/" + JUNK + "/enable", Map.of(), t.operator()),
            400,
            "bad_request"),
        // ---- sessions ----
        c(
            "listOnlineUsers",
            "page=abc",
            t -> t.api.get("/v1/online-users?page=" + JUNK),
            400,
            "bad_request"),
        c(
            "listOnlineUsers",
            "size=x",
            t -> t.api.get("/v1/online-users?size=" + JUNK),
            400,
            "bad_request"),
        c(
            "listOnlineUsers",
            "page=0",
            t -> t.api.get("/v1/online-users?page=0"),
            422,
            "validation_failed"),
        c(
            "listOnlineUsers",
            "size=101",
            t -> t.api.get("/v1/online-users?size=101"),
            422,
            "validation_failed"),
        c(
            "listUserSessions",
            "非 UUID",
            t -> t.api.get("/v1/users/" + JUNK + "/sessions"),
            400,
            "bad_request"),
        c(
            "listUserSessions",
            "操作人头不合法仍是 operator_required",
            t ->
                t.api.get(
                    "/v1/users/" + random + "/sessions", Map.of("X-Operator-Session-Ref", "x")),
            400,
            "operator_required"),
        c(
            "listUserSessions",
            "不存在的用户",
            t -> t.api.get("/v1/users/" + random + "/sessions"),
            404,
            "user_not_found"),
        c(
            "terminateSession",
            "非 UUID",
            t -> t.api.delete("/v1/sessions/" + JUNK, t.operator()),
            400,
            "bad_request"),
        c(
            "terminateSession",
            "不存在的会话",
            t -> t.api.delete("/v1/sessions/" + random, t.operator()),
            404,
            "session_not_found"),
        c(
            "terminateSession",
            "缺操作人头仍是 operator_required",
            t -> t.api.delete("/v1/sessions/" + random, Map.of()),
            400,
            "operator_required"),
        // ---- accounts ----
        c(
            "changeOwnPassword",
            "空对象",
            t -> t.api.postJson("/v1/accounts/" + random + "/password", Map.of(), t.operator()),
            422,
            "validation_failed"),
        c(
            "changeOwnPassword",
            "缺 clientIp",
            t ->
                t.api.postJson(
                    "/v1/accounts/" + random + "/password",
                    Map.of("currentPassword", "x", "newPassword", "y"),
                    t.operator()),
            422,
            "validation_failed"),
        c(
            "changeOwnPassword",
            "坏 JSON",
            t ->
                t.api.postJson(
                    "/v1/accounts/" + random + "/password", "{not json " + JUNK, t.operator()),
            400,
            "bad_request"),
        c(
            "changeOwnPassword",
            "空请求体",
            t -> t.api.postJson("/v1/accounts/" + random + "/password", "", t.operator()),
            400,
            "bad_request"),
        c(
            "changeOwnPassword",
            "accountId 非 UUID",
            t ->
                t.api.postJson(
                    "/v1/accounts/" + JUNK + "/password",
                    Map.of("currentPassword", "x", "newPassword", "y", "clientIp", IP),
                    t.operator()),
            400,
            "bad_request"),
        c(
            "changeOwnPassword",
            "缺操作人头仍是 operator_required",
            t ->
                t.api.postJson(
                    "/v1/accounts/" + random + "/password",
                    Map.of("currentPassword", "x", "newPassword", "y", "clientIp", IP),
                    Map.of()),
            400,
            "operator_required"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void everyErrorHasExactlyOneStatusAndIsDeclaredInTheContract(Case testCase) {
    Response response = testCase.call().apply(this);

    assertThat(response.status()).as(testCase.toString()).isEqualTo(testCase.status());
    assertThat(declaredStatuses(testCase.operationId()))
        .as("契约里 %s 声明了 %d", testCase.operationId(), testCase.status())
        .contains(testCase.status());
    if (testCase.code() == null) {
      assertThat(response.body()).isEmpty();
      return;
    }
    assertThat(response.contentType()).startsWith("application/problem+json");
    JsonNode problem = response.json();
    assertConforms("Problem", problem);
    assertThat(problem.get("code").asString()).isEqualTo(testCase.code());
    assertThat(problem.get("status").asInt()).isEqualTo(testCase.status());
    assertThat(response.body()).doesNotContain(JUNK).doesNotContain("hunter2");
    if (testCase.status() == 422) {
      assertThat(problem.get("errors")).as("422 带 errors[]").isNotEmpty();
      problem
          .get("errors")
          .forEach(
              error -> {
                assertThat(error.get("field").asString()).isNotBlank();
                assertThat(error.get("reason").asString()).isNotBlank();
              });
    } else {
      assertThat(problem.has("errors")).as("%s 不带 errors", testCase.code()).isFalse();
    }
  }

  @Test
  void everyContractOperationHasAtLeastOneErrorCase() {
    Set<String> covered = cases().stream().map(Case::operationId).collect(Collectors.toSet());
    Set<String> declared =
        OpenApiContract.operations().stream()
            .map(OpenApiContract.Operation::operationId)
            .collect(Collectors.toSet());
    assertThat(covered).containsExactlyInAnyOrderElementsOf(declared);
  }

  @Test
  void validationFailedIsNeverReturnedWithAnyOtherStatus() {
    for (Case testCase : cases()) {
      Response response = testCase.call().apply(this);
      if (response.body().contains("\"validation_failed\"")) {
        assertThat(response.status()).as(testCase.toString()).isEqualTo(422);
      }
      if (response.body().contains("\"bad_request\"")) {
        assertThat(response.status()).as(testCase.toString()).isEqualTo(400);
      }
    }
  }
}
