package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.UserFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * T125：强制下线的端到端行为（真实 PostgreSQL 与 Redis）——只终止目标会话，同用户其他会话、账号与用户状态不变；终止后校验立即 401 {@code
 * session_invalid}（撤销窗口 0）、会话从在线用户与用户会话中消失、账号可再次登录；自己的会话被拒；写操作日志；响应与日志里没有会话标识。
 */
class TerminateSessionIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final String IP = "203.0.113.7";

  @Value("${local.server.port}")
  int port;

  @Autowired PasswordHasher hasher;

  private ApiClient api;
  private UUID operatorUser;
  private UUID operatorAccount;
  private UUID target;
  private UUID accountA;
  private UUID accountB;
  private UUID other;

  private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  /** 一次登录的结果：明文会话标识（只用来断言校验结果与不外泄）与会话引用。 */
  private record Login(String sessionId, UUID ref) {}

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    UserFixtures data = new UserFixtures(jdbc);
    operatorUser = data.user("运维甲");
    operatorAccount = data.account(operatorUser, "ops");
    data.credential(operatorAccount, hasher.hash(PASSWORD));
    target = data.user("张三");
    accountA = data.account(target, "zhangsan-a");
    accountB = data.account(target, "zhangsan-b");
    data.credential(accountA, hasher.hash(PASSWORD));
    data.credential(accountB, hasher.hash(PASSWORD));
    other = data.user("李四");
    data.credential(data.account(other, "lisi"), hasher.hash(PASSWORD));
    logs.start();
    rootLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logs);
    logs.stop();
  }

  private Login login(String accountName) {
    Response response = api.login(accountName, PASSWORD, IP);
    assertThat(response.status()).isEqualTo(200);
    return new Login(
        response.json().get("sessionId").asString(),
        UUID.fromString(response.json().get("sessionRef").asString()));
  }

  private Map<String, String> operator(UUID operatorSessionRef) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Operator-User-Id", operatorUser.toString());
    headers.put("X-Operator-Account-Id", operatorAccount.toString());
    headers.put("X-Operator-Session-Ref", operatorSessionRef.toString());
    return headers;
  }

  private Response terminate(UUID ref) {
    return api.delete("/v1/sessions/" + ref, operator(UUID.randomUUID()));
  }

  private List<String> onlineUserIds() {
    return api.get("/v1/online-users")
        .json()
        .get("items")
        .valueStream()
        .map(item -> item.get("userId").asString())
        .toList();
  }

  private List<String> sessionRefsOf(UUID userId) {
    return api.get("/v1/users/" + userId + "/sessions")
        .json()
        .get("items")
        .valueStream()
        .map(item -> item.get("sessionRef").asString())
        .toList();
  }

  private String column(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id);
  }

  private List<ILoggingEvent> writeEvents() {
    return List.copyOf(logs.list).stream()
        .filter(e -> "uam.admin.write".equals(e.getFormattedMessage()))
        .toList();
  }

  private static Object field(ILoggingEvent event, String key) {
    return event.getKeyValuePairs().stream()
        .filter(kv -> kv.key.equals(key))
        .map(kv -> kv.value)
        .findFirst()
        .orElse(null);
  }

  @Test
  void onlyTheTargetSessionEndsAndTheSameUsersOtherSessionAndOthersAreUntouched() {
    Login a = login("zhangsan-a");
    Login b = login("zhangsan-b");
    Login stranger = login("lisi");

    Response response = terminate(a.ref());

    assertThat(response.status()).isEqualTo(204);
    assertThat(api.verify(a.sessionId()).status()).as("撤销窗口为 0，立即失效").isEqualTo(401);
    assertThat(api.verify(a.sessionId()).json().get("code").asString())
        .isEqualTo("session_invalid");
    assertThat(api.verify(b.sessionId()).status()).as("同用户的另一账号会话不受影响").isEqualTo(200);
    assertThat(api.verify(stranger.sessionId()).status()).isEqualTo(200);
    assertThat(sessionRefsOf(target)).containsExactly(b.ref().toString());
    assertThat(onlineUserIds()).contains(target.toString(), other.toString());
    assertThat(redis.hasKey("uam:sref:" + a.ref())).isFalse();
    assertThat(redis.hasKey("uam:acct:" + accountA + ":sess")).isFalse();
    assertThat(redis.opsForZSet().range("uam:online", 0, -1))
        .noneMatch(member -> member.endsWith("|" + a.ref()))
        .anyMatch(member -> member.endsWith("|" + b.ref()));
  }

  @Test
  void aUserWithNoSessionLeftDisappearsFromTheOnlineList() {
    Login a = login("zhangsan-a");
    Login b = login("zhangsan-b");

    terminate(a.ref());
    terminate(b.ref());

    assertThat(onlineUserIds()).doesNotContain(target.toString());
    assertThat(sessionRefsOf(target)).isEmpty();
  }

  @Test
  void accountAndUserStatusStayAsTheyWereAndTheAccountCanLogInAgain() {
    Login a = login("zhangsan-a");

    assertThat(terminate(a.ref()).status()).isEqualTo(204);

    assertThat(column("app_user", "status", target)).isEqualTo("ACTIVE");
    assertThat(column("account", "status", accountA)).isEqualTo("ACTIVE");
    assertThat(column("account", "status", accountB)).isEqualTo("ACTIVE");
    Login again = login("zhangsan-a");
    assertThat(again.ref()).isNotEqualTo(a.ref());
    assertThat(api.verify(again.sessionId()).status()).isEqualTo(200);
    assertThat(api.verify(a.sessionId()).status()).as("旧会话不复活").isEqualTo(401);
  }

  @Test
  void theOperatorsOwnSessionIsRefusedAndStaysValid() {
    Login own = login("ops");

    Response response = api.delete("/v1/sessions/" + own.ref(), operator(own.ref()));

    assertThat(response.status()).isEqualTo(409);
    assertThat(response.json().get("code").asString()).isEqualTo("cannot_terminate_own_session");
    assertThat(api.verify(own.sessionId()).status()).isEqualTo(200);
  }

  @Test
  void anAlreadyEndedOrUnknownSessionIsNotFound() {
    Login a = login("zhangsan-a");
    terminate(a.ref());

    Response again = terminate(a.ref());
    Response unknown = terminate(UUID.randomUUID());

    assertThat(again.status()).isEqualTo(404);
    assertThat(again.json().get("code").asString()).isEqualTo("session_not_found");
    assertThat(unknown.status()).isEqualTo(404);
  }

  @Test
  void aSessionTheOperatorsRequestLeavesBehindIsNotTheTargetOfAnotherRequest() {
    Login a = login("zhangsan-a");
    Login b = login("zhangsan-b");

    // 操作人当前会话是 b：可以下线 a，但不能下线 b
    assertThat(api.delete("/v1/sessions/" + a.ref(), operator(b.ref())).status()).isEqualTo(204);
    assertThat(api.delete("/v1/sessions/" + b.ref(), operator(b.ref())).status()).isEqualTo(409);
    assertThat(api.verify(b.sessionId()).status()).isEqualTo(200);
  }

  @Test
  void operatorHeadersAreRequiredAndNothingEndsWithoutThem() {
    Login a = login("zhangsan-a");

    Response response = api.delete("/v1/sessions/" + a.ref(), Map.of());

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("code").asString()).isEqualTo("operator_required");
    assertThat(api.verify(a.sessionId()).status()).isEqualTo(200);
  }

  @Test
  void everyOutcomeIsLoggedWithTheRefAndNeverTheSessionIdentifier() {
    Login a = login("zhangsan-a");
    Login own = login("ops");
    UUID unknown = UUID.randomUUID();

    UUID operatorRef = UUID.randomUUID();
    Response success = api.delete("/v1/sessions/" + a.ref(), operator(operatorRef));
    Response self = api.delete("/v1/sessions/" + own.ref(), operator(own.ref()));
    Response missing = api.delete("/v1/sessions/" + unknown, operator(operatorRef));

    List<ILoggingEvent> events = writeEvents();
    assertThat(events).hasSize(3);
    assertThat(field(events.get(0), "action")).isEqualTo("session.terminate");
    assertThat(field(events.get(0), "outcome")).isEqualTo("success");
    assertThat(field(events.get(0), "targetType")).isEqualTo("session");
    assertThat(field(events.get(0), "targetId")).isEqualTo(a.ref().toString());
    assertThat(field(events.get(0), "operatorUserId")).isEqualTo(operatorUser.toString());
    assertThat(field(events.get(0), "clientId")).isEqualTo("admin-center");
    assertThat(field(events.get(1), "outcome")).isEqualTo("rejected");
    assertThat(field(events.get(1), "reason")).isEqualTo("cannot_terminate_own_session");
    assertThat(field(events.get(2), "reason")).isEqualTo("session_not_found");
    assertThat(field(events.get(2), "targetId")).isEqualTo(unknown.toString());

    for (Login login : List.of(a, own)) {
      assertThat(success.body() + self.body() + missing.body()).doesNotContain(login.sessionId());
      assertThat(logs.list)
          .noneMatch(
              e ->
                  e.getFormattedMessage().contains(login.sessionId())
                      || e.getKeyValuePairs().stream()
                          .anyMatch(kv -> String.valueOf(kv.value).contains(login.sessionId())));
    }
  }
}
