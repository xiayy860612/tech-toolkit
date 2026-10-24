package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.infrastructure.redis.RedisGuards;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.TestSecrets;
import com.s2u2m.uam.support.UserFixtures;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * T135：改密的端到端行为（真实 PostgreSQL 与 Redis）——当前会话保持有效、同用户其他账号不受影响、旧密码不能登录；当前密码连续错误按 {@code
 * uam:throttle:pwchg:{sha256(accountId|来源地址)}} 限制（口径同登录）；日志与响应没有密码；{@code credential.updated_at}
 * 随改密前进。
 */
class ChangePasswordIT extends IntegrationTestBase {

  private static final String CURRENT = "Passw0rd-fake-1";
  private static final String NEW = "Another-fake-2";
  private static final String WRONG = "Wrong-fake-0";
  private static final String IP = "203.0.113.7";

  @Value("${local.server.port}")
  int port;

  @Autowired PasswordHasher hasher;

  private ApiClient api;
  private UUID userId;
  private UUID accountId;
  private UUID siblingAccountId;
  private UUID bystanderAccountId;

  private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    UserFixtures data = new UserFixtures(jdbc);
    userId = data.user("运维甲");
    accountId = data.account(userId, "ops");
    siblingAccountId = data.account(userId, "ops-2");
    data.credential(accountId, hasher.hash(CURRENT));
    data.credential(siblingAccountId, hasher.hash(CURRENT));
    bystanderAccountId = data.account(data.user("路人"), "bystander");
    data.credential(bystanderAccountId, hasher.hash(CURRENT));
    logs.start();
    rootLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logs);
    logs.stop();
  }

  private Map<String, String> operator(UUID account) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Operator-User-Id", userId.toString());
    headers.put("X-Operator-Account-Id", account.toString());
    headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
    return headers;
  }

  private Response change(String current, String next) {
    return change(current, next, IP);
  }

  private Response change(String current, String next, String ip) {
    return api.postJson(
        "/v1/accounts/" + accountId + "/password",
        Map.of("currentPassword", current, "newPassword", next, "clientIp", ip),
        operator(accountId));
  }

  private String loginSession(String accountName, String password) {
    Response response = api.login(accountName, password, IP);
    assertThat(response.status()).isEqualTo(200);
    return response.json().get("sessionId").asString();
  }

  private OffsetDateTime updatedAt(UUID account) {
    return jdbc.queryForObject(
        "SELECT updated_at FROM credential WHERE account_id = ?", OffsetDateTime.class, account);
  }

  private String hashOf(UUID account) {
    return jdbc.queryForObject(
        "SELECT password_hash FROM credential WHERE account_id = ?", String.class, account);
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
  void successKeepsTheCurrentSessionValidAndSwitchesWhichPasswordLogsIn() {
    String session = loginSession("ops", CURRENT);

    Response response = change(CURRENT, NEW);

    assertThat(response.status()).isEqualTo(204);
    assertThat(api.verify(session).status()).isEqualTo(200);
    assertThat(api.login("ops", CURRENT, IP).status()).isEqualTo(401);
    assertThat(api.login("ops", NEW, IP).status()).isEqualTo(200);
  }

  @Test
  void onlyThePathAccountChangesTheSiblingAndOtherUsersDoNot() {
    String siblingHash = hashOf(siblingAccountId);
    String bystanderHash = hashOf(bystanderAccountId);
    String siblingSession = loginSession("ops-2", CURRENT);

    assertThat(change(CURRENT, NEW).status()).isEqualTo(204);

    assertThat(hashOf(siblingAccountId)).isEqualTo(siblingHash);
    assertThat(hashOf(bystanderAccountId)).isEqualTo(bystanderHash);
    assertThat(api.verify(siblingSession).status()).isEqualTo(200);
    assertThat(api.login("ops-2", CURRENT, IP).status()).isEqualTo(200);
    assertThat(api.login("bystander", CURRENT, IP).status()).isEqualTo(200);
  }

  @Test
  void theStoredHashIsAnArgon2idHashOfTheNewPasswordAndUpdatedAtAdvances() {
    OffsetDateTime before = updatedAt(accountId);
    String oldHash = hashOf(accountId);

    assertThat(change(CURRENT, NEW).status()).isEqualTo(204);

    String hash = hashOf(accountId);
    assertThat(hash).isNotEqualTo(oldHash).doesNotContain(NEW);
    assertThat(hasher.matches(NEW, hash)).isTrue();
    assertThat(hasher.matches(CURRENT, hash)).isFalse();
    assertThat(updatedAt(accountId)).isAfter(before);
  }

  @Test
  void rejectedChangesLeaveTheCredentialUntouched() {
    OffsetDateTime before = updatedAt(accountId);
    String hash = hashOf(accountId);

    assertThat(change(WRONG, NEW).status()).isEqualTo(422);
    assertThat(change(CURRENT, "short").status()).isEqualTo(422);
    assertThat(change(CURRENT, CURRENT).status()).isEqualTo(422);

    assertThat(hashOf(accountId)).isEqualTo(hash);
    assertThat(updatedAt(accountId)).isEqualTo(before);
    assertThat(api.login("ops", CURRENT, IP).status()).isEqualTo(200);
  }

  @Test
  void failuresAreCountedUnderThePasswordChangeKeyAndBlockEvenTheRightPassword() {
    String key = "uam:throttle:pwchg:" + TestSecrets.sha256Hex(accountId + "|" + IP);

    for (int i = 0; i < 4; i++) {
      assertThat(change(WRONG, NEW).status()).isEqualTo(422);
    }
    assertThat(redis.opsForValue().get(key)).isEqualTo("4");
    Response fifth = change(WRONG, NEW);
    assertThat(fifth.status()).isEqualTo(422);
    assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(890L, 900L);

    Response blocked = change(CURRENT, NEW);

    assertThat(blocked.status()).isEqualTo(429);
    assertThat(blocked.retryAfter().getAsLong()).isBetween(890L, 900L);
    assertThat(blocked.json().get("code").asString()).isEqualTo("throttled");
    assertThat(api.login("ops", CURRENT, IP).status()).isEqualTo(200);
    assertThat(hasher.matches(CURRENT, hashOf(accountId))).isTrue();
    // 登录限流与改密限流互不影响
    assertThat(redis.keys("uam:throttle:login:*")).isEmpty();
  }

  @Test
  void theBlockIsPerSourceAndAnotherSourceCanStillChange() {
    for (int i = 0; i < 5; i++) {
      change(WRONG, NEW);
    }
    assertThat(change(CURRENT, NEW).status()).isEqualTo(429);

    assertThat(change(CURRENT, NEW, "198.51.100.9").status()).isEqualTo(204);
  }

  @Test
  void aSuccessfulChangeResetsTheCounter() {
    String key = "uam:throttle:pwchg:" + TestSecrets.sha256Hex(accountId + "|" + IP);
    change(WRONG, NEW);
    change(WRONG, NEW);
    assertThat(redis.opsForValue().get(key)).isEqualTo("2");

    assertThat(change(CURRENT, NEW).status()).isEqualTo(204);

    assertThat(redis.hasKey(key)).isFalse();
  }

  @Test
  void theBlockExpiresWithTheTtl() throws InterruptedException {
    LoginThrottlePolicy shortBlock = new LoginThrottlePolicy(2, Duration.ofSeconds(1));
    RedisGuards guards = new RedisGuards(redis, shortBlock);
    String subject = shortBlock.accountSubject(accountId, IP);
    guards.admitChange(subject);
    guards.admitChange(subject);
    assertThat(guards.admitChange(subject)).hasValue(1L);

    Thread.sleep(1_300);

    assertThat(guards.admitChange(subject)).isEmpty();
    guards.resetChange(subject);
    assertThat(redis.hasKey("uam:throttle:pwchg:" + subject)).isFalse();
  }

  @Test
  void anotherAccountThanTheOperatorsIsForbiddenAndNeverTouchesTheThrottle() {
    Response response =
        api.postJson(
            "/v1/accounts/" + siblingAccountId + "/password",
            Map.of("currentPassword", CURRENT, "newPassword", NEW, "clientIp", IP),
            operator(accountId));

    assertThat(response.status()).isEqualTo(403);
    assertThat(response.json().get("code").asString()).isEqualTo("client_forbidden");
    assertThat(api.login("ops-2", CURRENT, IP).status()).isEqualTo(200);
    assertThat(redis.keys("uam:throttle:pwchg:*")).isEmpty();
  }

  @Test
  void anUnknownAccountLooksLikeAWrongCurrentPassword() {
    UUID unknown = UUID.randomUUID();
    Response response =
        api.postJson(
            "/v1/accounts/" + unknown + "/password",
            Map.of("currentPassword", CURRENT, "newPassword", NEW, "clientIp", IP),
            operator(unknown));

    assertThat(response.status()).isEqualTo(422);
    assertThat(response.json().get("code").asString()).isEqualTo("current_password_incorrect");
  }

  @Test
  void everyOutcomeWritesALogWithoutAnyPasswordOrHash() {
    String oldHash = hashOf(accountId);

    change(WRONG, NEW);
    change(CURRENT, "short");
    change(CURRENT, NEW);

    List<ILoggingEvent> events = writeEvents();
    assertThat(events).hasSize(3);
    assertThat(events)
        .extracting(e -> field(e, "outcome"))
        .containsExactly("rejected", "rejected", "success");
    assertThat(events)
        .extracting(e -> field(e, "reason"))
        .containsExactly("current_password_incorrect", "weak_password", null);
    for (ILoggingEvent event : events) {
      assertThat(field(event, "action")).isEqualTo("account.password.change");
      assertThat(field(event, "targetType")).isEqualTo("account");
      assertThat(field(event, "targetId")).isEqualTo(accountId.toString());
      assertThat(field(event, "operatorUserId")).isEqualTo(userId.toString());
      assertThat(field(event, "operatorAccountId")).isEqualTo(accountId.toString());
      assertThat(field(event, "clientId")).isEqualTo("admin-center");
    }
    String everything =
        List.copyOf(logs.list).stream()
            .map(e -> e.getFormattedMessage() + " " + e.getKeyValuePairs())
            .reduce("", String::concat);
    assertThat(everything)
        .doesNotContain(CURRENT)
        .doesNotContain(NEW)
        .doesNotContain(WRONG)
        .doesNotContain(oldHash)
        .doesNotContain(hashOf(accountId));
  }

  @Test
  void responsesNeverEchoPasswords() {
    for (Response response :
        List.of(change(WRONG, NEW), change(CURRENT, "short"), change(CURRENT, CURRENT))) {
      assertThat(response.body()).doesNotContain(CURRENT).doesNotContain(NEW).doesNotContain(WRONG);
    }
    assertThat(change(CURRENT, NEW).body()).isEmpty();
  }
}
