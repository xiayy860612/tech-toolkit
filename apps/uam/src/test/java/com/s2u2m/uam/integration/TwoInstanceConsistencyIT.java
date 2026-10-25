package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.UamInstance;
import com.s2u2m.uam.support.UserFixtures;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * T158：两个 UAM 实例（各自独立的 Spring 上下文与端口）共享同一个 PostgreSQL 与 Redis（FR-037 ~ FR-040，SC-010）。
 *
 * <p>实例 A 是 {@code @SpringBootTest} 的实例，实例 B 由 {@link UamInstance} 启动；两者之间没有任何进程内共享状态，所以下面每一条一致性
 * 都只可能来自共享存储。
 */
class TwoInstanceConsistencyIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final String IP = "203.0.113.7";

  @Value("${local.server.port}")
  int portA;

  @Autowired PasswordHasher hasher;
  @Autowired Environment env;
  @Autowired ApplicationContext contextA;

  private UamInstance instanceB;
  private ApiClient a;
  private ApiClient b;
  private UUID operatorUser;
  private UUID operatorAccount;

  @BeforeEach
  void startSecondInstance() {
    instanceB = UamInstance.start(env);
    a = new ApiClient(portA, TEST_SERVICE_KEY);
    b = instanceB.api(TEST_SERVICE_KEY);
    UserFixtures data = new UserFixtures(jdbc);
    operatorUser = data.user("运维甲");
    operatorAccount = data.account(operatorUser, "ops");
    data.credential(operatorAccount, hasher.hash(PASSWORD));
    UUID user = data.user("张三");
    data.credential(data.account(user, "zhangsan"), hasher.hash(PASSWORD));
  }

  @AfterEach
  void stopSecondInstance() {
    instanceB.close();
  }

  private Map<String, String> operator() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Operator-User-Id", operatorUser.toString());
    headers.put("X-Operator-Account-Id", operatorAccount.toString());
    headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
    return headers;
  }

  private static String sessionId(Response login) {
    assertThat(login.status()).isEqualTo(200);
    return login.json().get("sessionId").asString();
  }

  @Test
  void theTwoInstancesAreReallySeparateApplications() {
    assertThat(instanceB.port()).isNotEqualTo(portA);
    assertThat(instanceB.context()).as("独立的 Spring 上下文").isNotSameAs(contextA);
    assertThat(instanceB.context().getBean(PasswordHasher.class)).isNotSameAs(hasher);
    assertThat(b.get("/actuator/health/readiness").status()).isEqualTo(200);
  }

  @Test
  void aSessionIssuedByOneInstanceIsRecognisedByTheOtherAtOnceInBothDirections() {
    String fromA = sessionId(a.login("zhangsan", PASSWORD, IP));

    Response verifiedOnB = b.verify(fromA);
    assertThat(verifiedOnB.status()).isEqualTo(200);
    assertThat(verifiedOnB.json().get("user").get("name").asString()).isEqualTo("张三");

    String fromB = sessionId(b.login("ops", PASSWORD, IP));
    assertThat(a.verify(fromB).status()).isEqualTo(200);
  }

  @Test
  void aSessionTerminatedViaOneInstanceIsInvalidOnTheOtherAtTheVeryNextVerify() {
    Response login = a.login("zhangsan", PASSWORD, IP);
    String sessionId = sessionId(login);
    String ref = login.json().get("sessionRef").asString();
    assertThat(b.verify(sessionId).status()).isEqualTo(200);

    Response terminated = a.delete("/v1/sessions/" + ref, operator());

    assertThat(terminated.status()).isEqualTo(204);
    Response afterOnB = b.verify(sessionId);
    assertThat(afterOnB.status()).isEqualTo(401);
    assertThat(afterOnB.body()).contains("\"code\":\"session_invalid\"");
    assertThat(a.verify(sessionId).status()).isEqualTo(401);
  }

  @Test
  void aLogoutOnOneInstanceInvalidatesTheSessionOnTheOther() {
    String sessionId = sessionId(b.login("zhangsan", PASSWORD, IP));
    assertThat(a.verify(sessionId).status()).isEqualTo(200);

    assertThat(b.logout(sessionId).status()).isEqualTo(204);

    assertThat(a.verify(sessionId).status()).isEqualTo(401);
  }

  @Test
  void disablingAUserViaOneInstanceEndsTheirSessionOnTheOther() {
    Response login = a.login("zhangsan", PASSWORD, IP);
    String sessionId = sessionId(login);
    UUID userId = UUID.fromString(login.json().get("user").get("id").asString());
    assertThat(b.verify(sessionId).status()).isEqualTo(200);

    Response disabled = a.postJson("/v1/users/" + userId + "/disable", Map.of(), operator());

    assertThat(disabled.status()).isEqualTo(200);
    assertThat(b.verify(sessionId).status()).isEqualTo(401);
    assertThat(b.login("zhangsan", PASSWORD, IP).status()).isEqualTo(401);
  }

  @Test
  void aUserCreatedViaOneInstanceIsVisibleOnTheOtherAndCanLogInThere() {
    Map<String, String> headers = operator();
    headers.put("Idempotency-Key", "two-instance-key-0001");

    Response created =
        a.postJson(
            "/v1/users",
            Map.of("userName", "王五", "accountName", "wangwu", "password", PASSWORD),
            headers);

    assertThat(created.status()).isEqualTo(201);
    String userId = created.json().get("id").asString();
    Response detailOnB = b.get("/v1/users/" + userId);
    assertThat(detailOnB.status()).isEqualTo(200);
    assertThat(detailOnB.json().get("name").asString()).isEqualTo("王五");
    assertThat(b.get("/v1/users?query=wangwu").json().get("items")).hasSize(1);
    Response loginOnB = b.login("wangwu", PASSWORD, IP);
    assertThat(loginOnB.status()).isEqualTo(200);
    assertThat(a.verify(sessionId(loginOnB)).status()).isEqualTo(200);
  }

  @Test
  void aNewLoginOnOneInstanceSupersedesTheSessionHeldViaTheOtherAndOnlineUsersAgree() {
    String first = sessionId(a.login("zhangsan", PASSWORD, IP));
    assertThat(b.verify(first).status()).isEqualTo(200);

    String second = sessionId(b.login("zhangsan", PASSWORD, IP));

    assertThat(a.verify(first).status()).isEqualTo(401);
    assertThat(a.verify(second).status()).isEqualTo(200);
    assertThat(a.get("/v1/online-users").json()).isEqualTo(b.get("/v1/online-users").json());
    assertThat(a.get("/v1/online-users").json().get("items")).hasSize(1);
  }
}
