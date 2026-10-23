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
import java.time.OffsetDateTime;
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
import tools.jackson.databind.JsonNode;

/**
 * T110：停用 / 启用用户的端到端行为（真实 PostgreSQL 与 Redis）——停用后其全部账号的会话结束、登录被拒（统一提示）、从在线列表消失；
 * 启用不恢复会话；账号自身状态不变；{@code app_user.updated_at} 随状态变更前进；幂等；写操作日志。
 */
class DisableUserIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final String IP = "203.0.113.7";

  @Value("${local.server.port}")
  int port;

  @Autowired PasswordHasher hasher;

  private ApiClient api;
  private UserFixtures data;
  private UUID operatorUser;
  private UUID operatorAccount;
  private UUID target;
  private UUID targetAccountA;
  private UUID targetAccountB;

  private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    data = new UserFixtures(jdbc);
    operatorUser = data.user("运维甲");
    operatorAccount = data.account(operatorUser, "ops");
    data.credential(operatorAccount, hasher.hash(PASSWORD));
    target = data.user("张三");
    targetAccountA = data.account(target, "zhangsan-a");
    targetAccountB = data.account(target, "zhangsan-b");
    data.credential(targetAccountA, hasher.hash(PASSWORD));
    data.credential(targetAccountB, hasher.hash(PASSWORD));
    logs.start();
    rootLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logs);
    logs.stop();
  }

  private Map<String, String> operator() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Operator-User-Id", operatorUser.toString());
    headers.put("X-Operator-Account-Id", operatorAccount.toString());
    headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
    return headers;
  }

  private Response disable(UUID userId) {
    return api.postJson("/v1/users/" + userId + "/disable", Map.of(), operator());
  }

  private Response enable(UUID userId) {
    return api.postJson("/v1/users/" + userId + "/enable", Map.of(), operator());
  }

  private String loginSession(String accountName) {
    Response response = api.login(accountName, PASSWORD, IP);
    assertThat(response.status()).isEqualTo(200);
    return response.json().get("sessionId").asString();
  }

  private List<String> onlineUserIds() {
    return api.get("/v1/online-users")
        .json()
        .get("items")
        .valueStream()
        .map(item -> item.get("userId").asString())
        .toList();
  }

  private OffsetDateTime updatedAt(UUID userId) {
    return jdbc.queryForObject(
        "SELECT updated_at FROM app_user WHERE id = ?", OffsetDateTime.class, userId);
  }

  private String status(UUID userId) {
    return jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, userId);
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
  void disablingEndsEverySessionOfEveryAccountRefusesLoginAndLeavesTheOnlineList() {
    String sessionA = loginSession("zhangsan-a");
    String sessionB = loginSession("zhangsan-b");
    String operatorSession = loginSession("ops");
    assertThat(onlineUserIds()).contains(target.toString(), operatorUser.toString());

    Response response = disable(target);

    assertThat(response.status()).isEqualTo(200);
    JsonNode detail = response.json();
    assertThat(detail.get("id").asString()).isEqualTo(target.toString());
    assertThat(detail.get("status").asString()).isEqualTo("DISABLED");
    assertThat(api.verify(sessionA).status()).isEqualTo(401);
    assertThat(api.verify(sessionB).status()).isEqualTo(401);
    assertThat(api.verify(sessionA).json().get("code").asString()).isEqualTo("session_invalid");
    assertThat(api.verify(operatorSession).status()).as("别的用户的会话不受影响").isEqualTo(200);
    assertThat(onlineUserIds()).containsExactly(operatorUser.toString());
    assertThat(redis.keys("uam:acct:" + targetAccountA + ":sess")).isEmpty();
    assertThat(redis.keys("uam:acct:" + targetAccountB + ":sess")).isEmpty();
    assertThat(redis.opsForZSet().range("uam:online", 0, -1))
        .noneMatch(member -> member.startsWith(target.toString()));

    Response wrongPassword = api.login("zhangsan-a", "Wrong-password-1", IP);
    Response refused = api.login("zhangsan-a", PASSWORD, IP);
    assertThat(refused.status()).isEqualTo(401);
    assertThat(refused.json().get("code").asString()).isEqualTo("invalid_credentials");
    assertThat(refused.json().get("detail").asString())
        .as("与密码错误完全一致，不泄露停用状态")
        .isEqualTo(wrongPassword.json().get("detail").asString());
  }

  @Test
  void accountStatusesStayAsTheyWereAndUpdatedAtAdvancesWithEachChange()
      throws InterruptedException {
    OffsetDateTime created = updatedAt(target);
    Thread.sleep(20);

    disable(target);
    OffsetDateTime disabledAt = updatedAt(target);
    Thread.sleep(20);
    enable(target);
    OffsetDateTime enabledAt = updatedAt(target);

    assertThat(disabledAt).isAfter(created);
    assertThat(enabledAt).isAfter(disabledAt);
    assertThat(
            jdbc.queryForList("SELECT status FROM account WHERE user_id = ?", String.class, target))
        .containsExactly("ACTIVE", "ACTIVE");
  }

  @Test
  void enablingRestoresTheAbilityToLogInButNotTheOldSessions() {
    String old = loginSession("zhangsan-a");
    disable(target);

    Response response = enable(target);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().get("status").asString()).isEqualTo("ACTIVE");
    assertThat(status(target)).isEqualTo("ACTIVE");
    assertThat(api.verify(old).status()).as("旧会话不恢复").isEqualTo(401);
    assertThat(onlineUserIds()).doesNotContain(target.toString());

    String fresh = loginSession("zhangsan-a");
    assertThat(api.verify(fresh).status()).isEqualTo(200);
    assertThat(onlineUserIds()).contains(target.toString());
  }

  @Test
  void disableAndEnableAreIdempotent() throws InterruptedException {
    assertThat(enable(target).status()).as("本来就是正常").isEqualTo(200);
    assertThat(disable(target).status()).isEqualTo(200);
    OffsetDateTime disabledAt = updatedAt(target);
    Thread.sleep(20);

    Response again = disable(target);

    assertThat(again.status()).isEqualTo(200);
    assertThat(again.json().get("status").asString()).isEqualTo("DISABLED");
    assertThat(updatedAt(target)).as("没有真正的变更就不动 updated_at").isEqualTo(disabledAt);
    assertThat(enable(target).status()).isEqualTo(200);
    assertThat(enable(target).status()).isEqualTo(200);
  }

  @Test
  void aRepeatedDisableStillEndsASessionThatSlippedIn() {
    disable(target);
    // 模拟「上一次停用在终止会话前崩溃」：已停用的用户名下残留一条会话
    String lingering = loginSessionBypassingTheDisabledCheck();
    assertThat(api.verify(lingering).status()).isEqualTo(200);

    assertThat(disable(target).status()).isEqualTo(200);

    assertThat(api.verify(lingering).status()).isEqualTo(401);
  }

  /** 临时把用户置回 ACTIVE 以登录，再直接改库成 DISABLED（不经过停用接口），造出残留会话。 */
  private String loginSessionBypassingTheDisabledCheck() {
    jdbc.update("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?", target);
    String session = loginSession("zhangsan-a");
    jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", target);
    return session;
  }

  @Test
  void rulesAreEnforcedByTheServerToo() {
    Response self = disable(operatorUser);
    assertThat(self.status()).isEqualTo(409);
    assertThat(self.json().get("code").asString()).isEqualTo("cannot_disable_self");
    assertThat(status(operatorUser)).isEqualTo("ACTIVE");

    assertThat(disable(target).status()).isEqualTo(200);
    // 现在只剩运维甲一个正常用户；用另一个操作人（不在库里）也不能停用他
    Map<String, String> stranger = operator();
    stranger.put("X-Operator-User-Id", UUID.randomUUID().toString());
    Response last = api.postJson("/v1/users/" + operatorUser + "/disable", Map.of(), stranger);
    assertThat(last.status()).isEqualTo(409);
    assertThat(last.json().get("code").asString()).isEqualTo("cannot_disable_last_active_user");
    assertThat(status(operatorUser)).isEqualTo("ACTIVE");
  }

  @Test
  void anUnknownUserIsNotFound() {
    Response response = disable(UUID.randomUUID());

    assertThat(response.status()).isEqualTo(404);
    assertThat(response.json().get("code").asString()).isEqualTo("user_not_found");
    assertThat(enable(UUID.randomUUID()).status()).isEqualTo(404);
  }

  @Test
  void everyOutcomeIsWrittenToTheWriteOperationLog() {
    disable(target);
    disable(operatorUser);
    disable(UUID.randomUUID());
    enable(target);

    List<ILoggingEvent> events = writeEvents();
    assertThat(events).hasSize(4);
    assertThat(field(events.get(0), "action")).isEqualTo("user.disable");
    assertThat(field(events.get(0), "outcome")).isEqualTo("success");
    assertThat(field(events.get(0), "targetId")).isEqualTo(target.toString());
    assertThat(field(events.get(0), "operatorUserId")).isEqualTo(operatorUser.toString());
    assertThat(field(events.get(1), "outcome")).isEqualTo("rejected");
    assertThat(field(events.get(1), "reason")).isEqualTo("cannot_disable_self");
    assertThat(field(events.get(2), "reason")).isEqualTo("user_not_found");
    assertThat(field(events.get(3), "action")).isEqualTo("user.enable");
    assertThat(field(events.get(3), "outcome")).isEqualTo("success");
    assertThat(field(events.get(3), "clientId")).isEqualTo("admin-center");
  }

  @Test
  void operatorHeadersAreRequiredAndNothingChangesWithoutThem() {
    Response response = api.postJson("/v1/users/" + target + "/disable", Map.of(), Map.of());

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("code").asString()).isEqualTo("operator_required");
    assertThat(status(target)).isEqualTo("ACTIVE");
  }
}
