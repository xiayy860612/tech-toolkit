package com.s2u2m.uam.contract;

import static com.s2u2m.uam.support.OpenApiContract.assertConforms;
import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.OpenApiContract;
import com.s2u2m.uam.support.OpenApiContract.Operation;
import com.s2u2m.uam.support.UserFixtures;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * T173：遍历契约里的<b>每一个</b>接口——不带密钥、错密钥、已撤销密钥 → 401 {@code client_unauthenticated} 且响应不透露哪里不对； 密钥有效但没有
 * {@code assert-end-user-context} 权限的客户端调用登录、校验会话、退出、修改密码与所有写操作 → 403 {@code client_forbidden}，
 * 只读查询仍 200。新增接口必须在下面的表里决定权限，否则本测试失败。
 */
class ClientAuthContractIT extends IntegrationTestBase {

  /** 每个接口（operationId）是否需要 {@code assert-end-user-context}；与契约里声明 403 的接口一一对应。 */
  private static final Map<String, Boolean> NEEDS_ASSERTION =
      Map.ofEntries(
          Map.entry("login", true),
          Map.entry("verifySession", true),
          Map.entry("logout", true),
          Map.entry("changeOwnPassword", true),
          Map.entry("createUser", true),
          Map.entry("disableUser", true),
          Map.entry("enableUser", true),
          Map.entry("terminateSession", true),
          Map.entry("listUsers", false),
          Map.entry("getUser", false),
          Map.entry("listOnlineUsers", false),
          Map.entry("listUserSessions", false));

  @Value("${local.server.port}")
  int port;

  private UUID existingUser;

  @BeforeEach
  void setUp() {
    UserFixtures data = new UserFixtures(jdbc);
    existingUser = data.user("张三");
    data.account(existingUser, "zhangsan");
  }

  private ApiClient client(String key) {
    return new ApiClient(port, key);
  }

  private String concretePath(Operation operation) {
    return operation
        .path()
        .replace("{userId}", existingUser.toString())
        .replace("{sessionRef}", UUID.randomUUID().toString())
        .replace("{accountId}", UUID.randomUUID().toString());
  }

  private Response call(ApiClient api, Operation operation) {
    // 过滤器先于参数校验：空 JSON 体与缺少操作人请求头都不应改变 401 / 403 的结果
    String body = "POST".equals(operation.method()) ? "{}" : null;
    return api.request(operation.method(), concretePath(operation), body, Map.of());
  }

  private static void assertProblem(Response response, int status, String code) {
    assertThat(response.status()).isEqualTo(status);
    assertThat(response.contentType()).startsWith("application/problem+json");
    JsonNode problem = response.json();
    assertConforms("Problem", problem);
    assertThat(problem.get("code").asString()).isEqualTo(code);
    assertThat(problem.get("status").asInt()).isEqualTo(status);
  }

  @Test
  void everyOperationInTheContractHasADecidedPermission() {
    List<Operation> operations = OpenApiContract.operations();

    Set<String> ids = operations.stream().map(Operation::operationId).collect(Collectors.toSet());
    assertThat(ids)
        .as("契约里新增 / 删除了接口：请在 NEEDS_ASSERTION 里决定它的权限")
        .isEqualTo(NEEDS_ASSERTION.keySet());
    for (Operation operation : operations) {
      assertThat(operation.declares403())
          .as("%s 是否声明 403 应与是否需要断言权限一致", operation.operationId())
          .isEqualTo(NEEDS_ASSERTION.get(operation.operationId()));
    }
  }

  @Test
  void everyOperationRejectsMissingWrongAndRevokedKeysIdentically() {
    for (Operation operation : OpenApiContract.operations()) {
      Response missing = call(client(null), operation);
      Response wrong = call(client("uamk_test_fake_key_wrong"), operation);
      Response revoked = call(client(REVOKED_SERVICE_KEY), operation);

      for (Response response : List.of(missing, wrong, revoked)) {
        assertProblem(response, 401, "client_unauthenticated");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
      }
      // 响应不透露哪里不对：三种情形的响应体逐字相同，也不回显提交的密钥
      assertThat(wrong.body()).as(operation.operationId()).isEqualTo(missing.body());
      assertThat(revoked.body()).as(operation.operationId()).isEqualTo(missing.body());
      assertThat(revoked.body()).doesNotContain(REVOKED_SERVICE_KEY);
    }
  }

  @Test
  void aKeyWithoutTheAssertionPermissionIsForbiddenOnLoginVerifyLogoutPasswordAndEveryWrite() {
    ApiClient readOnly = client(READ_ONLY_SERVICE_KEY);
    for (Operation operation : OpenApiContract.operations()) {
      if (!NEEDS_ASSERTION.get(operation.operationId())) {
        continue;
      }

      Response response = call(readOnly, operation);

      assertProblem(response, 403, "client_forbidden");
      assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
      assertThat(response.body()).as(operation.operationId()).doesNotContain(READ_ONLY_SERVICE_KEY);
    }
  }

  @Test
  void aKeyWithoutTheAssertionPermissionStillReadsEverythingReadOnly() {
    ApiClient readOnly = client(READ_ONLY_SERVICE_KEY);
    for (Operation operation : OpenApiContract.operations()) {
      if (NEEDS_ASSERTION.get(operation.operationId())) {
        continue;
      }

      Response response = call(readOnly, operation);

      assertThat(response.status()).as(operation.operationId()).isEqualTo(200);
    }
  }

  @Test
  void bothKeysOfAClientInRotationAreAcceptedOnEveryKindOfCall() {
    for (String key : List.of(TEST_SERVICE_KEY, TEST_SERVICE_KEY_NEXT)) {
      ApiClient api = client(key);
      assertThat(api.get("/v1/users").status()).isEqualTo(200);
      // 写类接口：到达业务层（账号不存在 → invalid_credentials），而不是 401 / 403
      Response login = api.login("nobody", "whatever-password-1", "192.0.2.1");
      assertProblem(login, 401, "invalid_credentials");
    }
  }
}
