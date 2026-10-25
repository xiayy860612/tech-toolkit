package com.s2u2m.uam.contract;

import static com.s2u2m.uam.support.OpenApiContract.assertConforms;
import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.application.AccountProvisioningService.Provisioned;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.OpenApiContract;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T050：{@code POST /v1/auth/login} 与 {@code /v1/auth/sessions/verify} 的请求与响应按 OpenAPI 校验；失败限制。 */
class AuthContractIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Value("${local.server.port}")
  int port;

  @Autowired AccountProvisioningService provisioning;

  private ApiClient api;
  private Provisioned alice;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    alice = provisioning.createUserWithAccount("爱丽丝", "Alice", PASSWORD);
  }

  private Response login(String name, String password, String ip) {
    Map<String, String> request = Map.of("accountName", name, "password", password, "clientIp", ip);
    assertConforms("LoginRequest", JSON.valueToTree(request));
    return api.login(name, password, ip);
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
  void loginSuccessConformsToTheContract() {
    Response response = login("alice", PASSWORD, "203.0.113.7");

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    JsonNode body = response.json();
    assertConforms("LoginResponse", body);
    assertThat(body.get("user").get("id").asString()).isEqualTo(alice.userId().toString());
    assertThat(body.get("user").get("name").asString()).isEqualTo("爱丽丝");
    assertThat(body.get("account").get("id").asString()).isEqualTo(alice.accountId().toString());
    assertThat(body.get("account").get("loginName").asString()).isEqualTo("Alice");
    assertThat(body.get("account").get("kind").asString()).isEqualTo("PASSWORD");
    assertThat(body.get("sessionId").asString()).matches("[A-Za-z0-9_-]{43}");
    assertThat(body.get("maxAgeSeconds").asLong()).isBetween(43_190L, 43_200L);
    assertThat(Instant.parse(body.get("expiresAt").asString())).isAfter(Instant.now());
  }

  @Test
  void accountNameWithNulOrLoneSurrogateFailsLikeAnUnknownAccount() {
    Response unknown = login("nobody", PASSWORD, "203.0.113.1");
    assertProblem(unknown, 401, "invalid_credentials");

    for (String name : new String[] {"a\u0000b", "\u0000", "a\uD800b"}) {
      Response response = login(name, PASSWORD, "203.0.113.2");

      assertProblem(response, 401, "invalid_credentials");
      assertThat(response.json().get("detail")).isEqualTo(unknown.json().get("detail"));
      assertThat(response.json().get("title")).isEqualTo(unknown.json().get("title"));
      assertThat(response.body()).doesNotContain(name);
    }
    assertThat(redis.keys("uam:sess:*")).isEmpty();
  }

  @Test
  void loginNeverLeaksPasswordOrHash() {
    Response response = login("alice", PASSWORD, "203.0.113.7");

    assertThat(OpenApiContract.fieldNames(response.json()))
        .doesNotContain("password", "passwordHash", "hash");
    assertThat(response.body()).doesNotContain(PASSWORD).doesNotContain("{argon2");
  }

  @Test
  void verifyConformsToTheContractAndReturnsTheSessionData() {
    JsonNode loggedIn = login("alice", PASSWORD, "203.0.113.7").json();
    Map<String, String> request = Map.of("sessionId", loggedIn.get("sessionId").asString());
    assertConforms("VerifyRequest", JSON.valueToTree(request));

    Response response = api.verify(loggedIn.get("sessionId").asString());

    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.json();
    assertConforms("VerifyResponse", body);
    assertThat(body.get("user")).isEqualTo(loggedIn.get("user"));
    assertThat(body.get("account")).isEqualTo(loggedIn.get("account"));
    assertThat(body.get("sessionRef")).isEqualTo(loggedIn.get("sessionRef"));
    assertThat(response.body()).doesNotContain(loggedIn.get("sessionId").asString());
  }

  @Test
  void twoAccountsOfOneUserAreRecognisedAsTheSameUser() {
    provisioning.addAccount(alice.userId(), "alice.second", PASSWORD);

    JsonNode first = login("alice", PASSWORD, "203.0.113.7").json();
    JsonNode second = login("alice.second", PASSWORD, "203.0.113.7").json();

    assertThat(second.get("user").get("id")).isEqualTo(first.get("user").get("id"));
    assertThat(second.get("account").get("id")).isNotEqualTo(first.get("account").get("id"));
    assertThat(api.verify(first.get("sessionId").asString()).status()).isEqualTo(200);
    assertThat(api.verify(second.get("sessionId").asString()).status()).isEqualTo(200);
  }

  @Test
  void aSecondLoginOfTheSameAccountEndsTheFirstSession() {
    String first = login("alice", PASSWORD, "203.0.113.7").json().get("sessionId").asString();
    String second = login("alice", PASSWORD, "198.51.100.9").json().get("sessionId").asString();

    assertProblem(api.verify(first), 401, "session_invalid");
    assertThat(api.verify(second).status()).isEqualTo(200);
  }

  @Test
  void everyKindOfCredentialFailureLooksTheSame() {
    Provisioned bob = provisioning.createUserWithAccount("鲍勃", "bob", PASSWORD);
    jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", bob.userId());
    Provisioned carol = provisioning.createUserWithAccount("卡罗", "carol", PASSWORD);
    jdbc.update("UPDATE account SET status = 'DISABLED' WHERE id = ?", carol.accountId());

    Response unknown = login("nobody", PASSWORD, "203.0.113.1");
    Response wrong = login("alice", "Wrong-password-1", "203.0.113.2");
    Response disabledUser = login("bob", PASSWORD, "203.0.113.3");
    Response disabledAccount = login("carol", PASSWORD, "203.0.113.4");

    for (Response response : new Response[] {unknown, wrong, disabledUser, disabledAccount}) {
      assertProblem(response, 401, "invalid_credentials");
    }
    assertThat(wrong.json().get("detail")).isEqualTo(unknown.json().get("detail"));
    assertThat(disabledUser.json().get("detail")).isEqualTo(unknown.json().get("detail"));
    assertThat(disabledAccount.json().get("detail")).isEqualTo(unknown.json().get("detail"));
    assertThat(redis.keys("uam:sess:*")).isEmpty();
  }

  @Test
  void loginRulesViolationsAre422ValidationFailed() {
    assertProblem(
        api.postJson("/v1/auth/login", Map.of("password", "x", "clientIp", "1.2.3.4")),
        422,
        "validation_failed");
    assertProblem(
        api.postJson(
            "/v1/auth/login",
            Map.of("accountName", "alice", "password", "", "clientIp", "1.2.3.4")),
        422,
        "validation_failed");
    assertProblem(
        api.postJson("/v1/auth/login", Map.of("accountName", "alice", "password", "x")),
        422,
        "validation_failed");
    assertProblem(
        api.postJson(
            "/v1/auth/login",
            Map.of("accountName", "a".repeat(65), "password", "x", "clientIp", "1.2.3.4")),
        422,
        "validation_failed");
    assertProblem(api.postJson("/v1/auth/login", "{not json"), 400, "bad_request");

    Response missing =
        api.postJson("/v1/auth/login", Map.of("password", "x", "clientIp", "1.2.3.4"));
    assertThat(missing.json().get("errors").get(0).get("field").asString())
        .isEqualTo("accountName");
    assertThat(missing.json().get("errors").get(0).get("reason").asString()).isEqualTo("required");
  }

  @Test
  void verifyWithAnUnknownBlankOrMissingSessionIdIsSessionInvalid() {
    assertProblem(api.verify("A".repeat(43)), 401, "session_invalid");
    assertProblem(api.verify(" "), 401, "session_invalid");
    assertProblem(api.verify("x".repeat(500)), 401, "session_invalid");
    assertProblem(api.postJson("/v1/auth/sessions/verify", Map.of()), 401, "session_invalid");
  }

  @Test
  void authEndpointsNeedNoOperatorHeadersButNeedTheServiceKey() {
    assertThat(login("alice", PASSWORD, "203.0.113.7").status()).isEqualTo(200);

    ApiClient anonymous = new ApiClient(port, null);
    assertProblem(anonymous.login("alice", PASSWORD, "203.0.113.7"), 401, "client_unauthenticated");
    assertProblem(anonymous.verify("A".repeat(43)), 401, "client_unauthenticated");
  }

  @Test
  void fiveFailuresFromOneSourceBlockThatSourceEvenForTheRightPassword() {
    for (int i = 0; i < 5; i++) {
      assertProblem(
          login("alice", "Wrong-password-" + i, "203.0.113.7"), 401, "invalid_credentials");
    }

    Response blocked = login("alice", PASSWORD, "203.0.113.7");

    assertProblem(blocked, 429, "throttled");
    assertThat(blocked.retryAfter()).isPresent();
    assertThat(blocked.retryAfter().getAsLong()).isBetween(1L, 900L);

    // 其他来源仍可登录：不锁账号
    assertThat(login("alice", PASSWORD, "198.51.100.9").status()).isEqualTo(200);
    // 同一来源对其他账号不受影响
    provisioning.createUserWithAccount("鲍勃", "bob", PASSWORD);
    assertThat(login("bob", PASSWORD, "203.0.113.7").status()).isEqualTo(200);
    // 账号名大小写不影响限流口径
    assertProblem(login("ALICE", PASSWORD, "203.0.113.7"), 429, "throttled");
  }

  @Test
  void aSuccessfulLoginResetsTheFailureCounter() {
    for (int i = 0; i < 4; i++) {
      login("alice", "Wrong-password-" + i, "203.0.113.7");
    }
    assertThat(login("alice", PASSWORD, "203.0.113.7").status()).isEqualTo(200);

    for (int i = 0; i < 4; i++) {
      assertProblem(
          login("alice", "Wrong-password-" + i, "203.0.113.7"), 401, "invalid_credentials");
    }
    assertThat(login("alice", PASSWORD, "203.0.113.7").status()).isEqualTo(200);
  }

  @Test
  void unknownAccountsAreThrottledToo() {
    for (int i = 0; i < 5; i++) {
      assertProblem(login("nobody", PASSWORD, "203.0.113.7"), 401, "invalid_credentials");
    }

    assertProblem(login("nobody", PASSWORD, "203.0.113.7"), 429, "throttled");
  }
}
