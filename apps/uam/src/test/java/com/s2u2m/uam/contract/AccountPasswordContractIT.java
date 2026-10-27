package com.s2u2m.uam.contract;

import static com.s2u2m.uam.support.OpenApiContract.assertConforms;
import static com.s2u2m.uam.support.OpenApiContract.fieldNames;
import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.UserFixtures;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * T136：{@code POST /v1/accounts/{accountId}/password} 的 204 / 400 / 403 / 422 / 429 按 OpenAPI
 * 校验；响应没有任何密码或哈希。
 */
class AccountPasswordContractIT extends IntegrationTestBase {

  private static final String CURRENT = "Passw0rd-fake-1";
  private static final String NEW = "Another-fake-2";
  private static final String IP = "203.0.113.7";

  @Value("${local.server.port}")
  int port;

  @Autowired PasswordHasher hasher;

  private ApiClient api;
  private UUID userId;
  private UUID accountId;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    UserFixtures data = new UserFixtures(jdbc);
    userId = data.user("运维甲");
    accountId = data.account(userId, "ops");
    data.credential(accountId, hasher.hash(CURRENT));
  }

  private Map<String, String> operator(UUID account) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Operator-User-Id", userId.toString());
    headers.put("X-Operator-Account-Id", account.toString());
    headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
    return headers;
  }

  private Response change(Object body, Map<String, String> headers) {
    return api.postJson("/v1/accounts/" + accountId + "/password", body, headers);
  }

  private Map<String, String> body(String current, String next) {
    return Map.of("currentPassword", current, "newPassword", next, "clientIp", IP);
  }

  private void assertProblem(Response response, int status, String code) {
    assertThat(response.status()).isEqualTo(status);
    assertThat(response.contentType()).startsWith("application/problem+json");
    JsonNode problem = response.json();
    assertConforms("Problem", problem);
    assertThat(problem.get("code").asString()).isEqualTo(code);
    assertThat(problem.get("status").asInt()).isEqualTo(status);
    assertThat(response.body())
        .doesNotContain(CURRENT)
        .doesNotContain(NEW)
        .doesNotContain("argon2");
    assertThat(fieldNames(problem)).doesNotContain("currentPassword", "newPassword", "password");
  }

  @Test
  void successIs204WithNoBody() {
    Response response = change(body(CURRENT, NEW), operator(accountId));

    assertThat(response.status()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
  }

  @Test
  void operatorHeadersAreRequiredAndMalformedOnesAreRejected() {
    assertProblem(change(body(CURRENT, NEW), Map.of()), 400, "operator_required");

    Map<String, String> partial = operator(accountId);
    partial.remove("X-Operator-Session-Ref");
    assertProblem(change(body(CURRENT, NEW), partial), 400, "operator_required");

    Map<String, String> malformed = operator(accountId);
    malformed.put("X-Operator-Account-Id", "not-a-uuid");
    assertProblem(change(body(CURRENT, NEW), malformed), 400, "operator_required");
  }

  @Test
  void aBodyThatBreaksRulesIs422ValidationFailedWithFieldReasons() {
    Response missingIp =
        change(Map.of("currentPassword", CURRENT, "newPassword", NEW), operator(accountId));
    assertProblem(missingIp, 422, "validation_failed");
    assertThat(missingIp.json().get("errors").get(0).get("field").asString()).isEqualTo("clientIp");

    Response missingCurrent =
        change(Map.of("newPassword", NEW, "clientIp", IP), operator(accountId));
    assertProblem(missingCurrent, 422, "validation_failed");
  }

  @Test
  void anUnparseableRequestIs400BadRequest() {
    assertProblem(change("{not json", operator(accountId)), 400, "bad_request");
    assertProblem(change("", operator(accountId)), 400, "bad_request");
  }

  @Test
  void validationFailuresAre422WithStableCodes() {
    assertProblem(
        change(body("Wrong-fake-0", NEW), operator(accountId)), 422, "current_password_incorrect");

    Response weak = change(body(CURRENT, "short"), operator(accountId));
    assertProblem(weak, 422, "weak_password");
    JsonNode error = weak.json().get("errors").get(0);
    assertThat(error.get("field").asString()).isEqualTo("newPassword");
    assertThat(error.get("reason").asString()).isEqualTo("too_short");

    assertProblem(change(body(CURRENT, CURRENT), operator(accountId)), 422, "same_as_current");
  }

  @Test
  void anotherAccountThanTheOperatorsIs403AndNothingChanges() {
    UUID other = new UserFixtures(jdbc).account(userId, "ops-2");
    String before =
        jdbc.queryForObject(
            "SELECT password_hash FROM credential WHERE account_id = ?", String.class, accountId);

    Response response = change(body(CURRENT, NEW), operator(other));

    assertProblem(response, 403, "operator_forbidden");
    assertThat(
            jdbc.queryForObject(
                "SELECT password_hash FROM credential WHERE account_id = ?",
                String.class,
                accountId))
        .isEqualTo(before);
  }

  @Test
  void repeatedWrongCurrentPasswordsEndIn429WithRetryAfter() {
    for (int i = 0; i < 5; i++) {
      assertThat(change(body("Wrong-fake-0", NEW), operator(accountId)).status()).isEqualTo(422);
    }

    Response blocked = change(body(CURRENT, NEW), operator(accountId));

    assertProblem(blocked, 429, "throttled");
    assertThat(blocked.retryAfter()).isPresent();
    assertThat(blocked.retryAfter().getAsLong()).isBetween(1L, 900L);
  }
}
