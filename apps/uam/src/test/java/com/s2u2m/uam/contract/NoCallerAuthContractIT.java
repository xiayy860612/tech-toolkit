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
 * T201：UAM 不认证调用方（网络边界是唯一保护，服务身份后续另行实现）。遍历契约里的<b>每一个</b>接口：不带任何 {@code Authorization}
 * 也被正常处理；写接口缺操作人请求头仍 → 400 {@code operator_required}（{@code /v1/auth/**} 除外）；契约里只有改他人密码声明 403
 * {@code operator_forbidden}。 新增接口必须在下面的表里决定是否需要操作人头，否则本测试失败。
 */
class NoCallerAuthContractIT extends IntegrationTestBase {

  /** 每个接口（operationId）是否是要求操作人请求头的写接口。 */
  private static final Map<String, Boolean> NEEDS_OPERATOR =
      Map.ofEntries(
          Map.entry("login", false),
          Map.entry("verifySession", false),
          Map.entry("logout", false),
          Map.entry("changeOwnPassword", true),
          Map.entry("createUser", true),
          Map.entry("disableUser", true),
          Map.entry("enableUser", true),
          Map.entry("terminateSession", true),
          Map.entry("listUsers", false),
          Map.entry("getUser", false),
          Map.entry("listOnlineUsers", false),
          Map.entry("listUserSessions", false));

  private static final Set<String> READS =
      Set.of("listUsers", "getUser", "listOnlineUsers", "listUserSessions");

  @Value("${local.server.port}")
  int port;

  private ApiClient api;
  private UUID existingUser;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    UserFixtures data = new UserFixtures(jdbc);
    existingUser = data.user("张三");
    data.account(existingUser, "zhangsan");
  }

  private String concretePath(Operation operation) {
    return operation
        .path()
        .replace("{userId}", existingUser.toString())
        .replace("{sessionRef}", UUID.randomUUID().toString())
        .replace("{accountId}", UUID.randomUUID().toString());
  }

  private Response call(Operation operation) {
    // 改密接口先做请求体校验（Bean Validation），所以给一个合法的体，才能观察到操作人请求头这一层
    String body =
        "changeOwnPassword".equals(operation.operationId())
            ? "{\"currentPassword\":\"x\",\"newPassword\":\"y\",\"clientIp\":\"192.0.2.1\"}"
            : "POST".equals(operation.method()) ? "{}" : null;
    return api.request(operation.method(), concretePath(operation), body, Map.of());
  }

  @Test
  void everyOperationInTheContractHasADecision() {
    List<Operation> operations = OpenApiContract.operations();

    Set<String> ids = operations.stream().map(Operation::operationId).collect(Collectors.toSet());
    assertThat(ids)
        .as("契约里新增 / 删除了接口：请在 NEEDS_OPERATOR 里决定它是否需要操作人头")
        .isEqualTo(NEEDS_OPERATOR.keySet());
  }

  @Test
  void onlyChangingSomeoneElsesPasswordDeclaresForbidden() {
    for (Operation operation : OpenApiContract.operations()) {
      assertThat(operation.declares403())
          .as("%s 是否声明 403", operation.operationId())
          .isEqualTo("changeOwnPassword".equals(operation.operationId()));
    }
  }

  @Test
  void everyReadWorksWithoutAnyAuthorization() {
    for (Operation operation : OpenApiContract.operations()) {
      if (!READS.contains(operation.operationId())) {
        continue;
      }

      Response response = call(operation);

      assertThat(response.status()).as(operation.operationId()).isEqualTo(200);
    }
  }

  @Test
  void everyWriteWithoutOperatorHeadersIsBadRequestNotUnauthorized() {
    for (Operation operation : OpenApiContract.operations()) {
      if (!NEEDS_OPERATOR.get(operation.operationId())) {
        continue;
      }

      Response response = call(operation);

      assertThat(response.status()).as(operation.operationId()).isEqualTo(400);
      assertThat(response.contentType()).startsWith("application/problem+json");
      JsonNode problem = response.json();
      assertConforms("Problem", problem);
      assertThat(problem.get("code").asString()).isEqualTo("operator_required");
    }
  }

  @Test
  void noResponseAsksForCredentials() {
    for (Operation operation : OpenApiContract.operations()) {
      Response response = call(operation);

      assertThat(response.headers().firstValue("WWW-Authenticate"))
          .as(operation.operationId())
          .isEmpty();
    }
  }

  @Test
  void authEndpointsReachTheBusinessLayerWithoutAnyAuthorizationOrOperatorHeaders() {
    Response login = api.login("nobody", "whatever-password-1", "192.0.2.1");

    assertThat(login.status()).isEqualTo(401);
    assertThat(login.json().get("code").asString()).isEqualTo("invalid_credentials");
    assertThat(api.verify("not-a-session").status()).isEqualTo(401);
    assertThat(api.logout("not-a-session").status()).isEqualTo(204);
  }

  @Test
  void prometheusIsReadableWithoutAuthorization() {
    Response response = api.get("/actuator/prometheus");

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).contains("application=\"uam\"");
  }

  @Test
  void healthProbesAreReadableWithoutAuthorization() {
    assertThat(api.get("/actuator/health/liveness").status()).isEqualTo(200);
    assertThat(api.get("/actuator/health/readiness").status()).isEqualTo(200);
  }
}
