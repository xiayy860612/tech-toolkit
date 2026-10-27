package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.UserFixtures;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/** T097：{@code POST /v1/users} 的端到端行为（真实 PostgreSQL 与 Redis）：单事务、账号名冲突、幂等重放、密码只以哈希落库。 */
class CreateUserIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final String KEY = "0123456789abcdef-key-1";

  @Value("${local.server.port}")
  int port;

  private ApiClient api;
  private final UUID operatorUser = UUID.randomUUID();
  private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    logs.start();
    rootLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logs);
    logs.stop();
  }

  private Map<String, String> headers(String idempotencyKey) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Operator-User-Id", operatorUser.toString());
    headers.put("X-Operator-Account-Id", UUID.randomUUID().toString());
    headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
    if (idempotencyKey != null) {
      headers.put("Idempotency-Key", idempotencyKey);
    }
    return headers;
  }

  private Response create(String key, String userName, String accountName, String password) {
    return api.postJson(
        "/v1/users",
        Map.of("userName", userName, "accountName", accountName, "password", password),
        headers(key));
  }

  private long rows(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
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
  void createsTheUserAccountAndCredentialTogetherAndTheAccountCanLogInAtOnce() {
    Response response = create(KEY, "张三", "Zhang.San", PASSWORD);

    assertThat(response.status()).isEqualTo(201);
    JsonNode body = response.json();
    assertThat(body.get("name").asString()).isEqualTo("张三");
    assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(body.get("accounts")).hasSize(1);
    assertThat(body.get("accounts").get(0).get("loginName").asString()).isEqualTo("Zhang.San");
    assertThat(body.get("accounts").get(0).get("kind").asString()).isEqualTo("PASSWORD");
    assertThat(rows("app_user")).isEqualTo(1);
    assertThat(rows("account")).isEqualTo(1);
    assertThat(rows("credential")).isEqualTo(1);
    assertThat(api.login("zhang.san", PASSWORD, "203.0.113.7").status()).isEqualTo(200);
    assertThat(api.get("/v1/users/" + body.get("id").asString()).json()).isEqualTo(body);
  }

  @Test
  void updatedAtIsSetOnTheUserAndTheCredential() {
    create(KEY, "张三", "zhangsan", PASSWORD);

    assertThat(jdbc.queryForObject("SELECT updated_at FROM app_user", Instant.class)).isNotNull();
    assertThat(jdbc.queryForObject("SELECT updated_at FROM credential", Instant.class)).isNotNull();
  }

  @Test
  void thePasswordIsOnlyStoredAsAHashAndNeverAppearsInTheResponseTheSnapshotOrTheLogs() {
    Response response = create(KEY, "张三", "zhangsan", PASSWORD);

    String hash = jdbc.queryForObject("SELECT password_hash FROM credential", String.class);
    assertThat(hash).startsWith("{argon2@SpringSecurity_v5_8}$argon2id$").doesNotContain(PASSWORD);
    assertThat(response.body()).doesNotContain(PASSWORD).doesNotContain("argon2");
    List<String> snapshots =
        redis.keys("uam:idem:*").stream().map(redis.opsForValue()::get).toList();
    assertThat(snapshots).hasSize(1);
    assertThat(snapshots.get(0)).doesNotContain(PASSWORD).doesNotContain("argon2");
    assertThat(logs.list)
        .allSatisfy(
            event -> {
              assertThat(event.getFormattedMessage()).doesNotContain(PASSWORD);
              assertThat(String.valueOf(event.getKeyValuePairs())).doesNotContain(PASSWORD);
              assertThat(String.valueOf(event.getArgumentArray())).doesNotContain(PASSWORD);
            });
  }

  @Test
  void aSuccessfulCreateWritesOneUamAdminWriteLog() {
    String userId = create(KEY, "张三", "zhangsan", PASSWORD).json().get("id").asString();

    assertThat(writeEvents()).hasSize(1);
    ILoggingEvent event = writeEvents().get(0);
    assertThat(field(event, "action")).isEqualTo("user.create");
    assertThat(field(event, "outcome")).isEqualTo("success");
    assertThat(field(event, "targetType")).isEqualTo("user");
    assertThat(field(event, "targetId")).isEqualTo(userId);
    assertThat(field(event, "operatorUserId")).isEqualTo(operatorUser.toString());
    assertThat(field(event, "clientId")).isNull();
  }

  @Test
  void aTakenAccountNameIsRejectedWithoutCaseSensitivityAndLeavesNoHalfMadeUser() {
    create(KEY, "张三", "Zhang.San", PASSWORD);

    Response response = create("another-key-0123456789", "李四", "zHANG.sAN", PASSWORD);

    assertThat(response.status()).isEqualTo(409);
    assertThat(response.contentType()).startsWith("application/problem+json");
    assertThat(response.json().get("code").asString()).isEqualTo("account_name_taken");
    assertThat(rows("app_user")).isEqualTo(1);
    assertThat(rows("account")).isEqualTo(1);
    assertThat(rows("credential")).isEqualTo(1);
    ILoggingEvent rejected = writeEvents().get(1);
    assertThat(field(rejected, "outcome")).isEqualTo("rejected");
    assertThat(field(rejected, "reason")).isEqualTo("account_name_taken");
    assertThat(field(rejected, "action")).isEqualTo("user.create");
  }

  @Test
  void accountNamesOfDisabledUsersAndDisabledAccountsStayTaken() {
    UserFixtures data = new UserFixtures(jdbc);
    UUID disabledUser = data.user("已停用用户", "DISABLED", Instant.now());
    data.account(disabledUser, "Ghost.One", "ACTIVE", Instant.now());
    UUID activeUser = data.user("正常用户");
    data.account(activeUser, "Ghost.Two", "DISABLED", Instant.now());

    assertThat(create(KEY, "新人", "ghost.one", PASSWORD).status()).isEqualTo(409);
    assertThat(create("another-key-0123456789", "新人", "GHOST.TWO", PASSWORD).status())
        .isEqualTo(409);
    assertThat(rows("app_user")).isEqualTo(2);
  }

  @Test
  void replayingTheSameKeyWithinTheWindowReturnsTheOriginal201() {
    Response first = create(KEY, "张三", "zhangsan", PASSWORD);

    Response replay = create(KEY, "张三", "zhangsan", PASSWORD);

    assertThat(first.status()).isEqualTo(201);
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.json()).isEqualTo(first.json());
    assertThat(rows("app_user")).isEqualTo(1);
    assertThat(rows("credential")).isEqualTo(1);
    assertThat(writeEvents()).as("重放不重复记日志").hasSize(1);
    List<String> keys = List.copyOf(redis.keys("uam:idem:*"));
    assertThat(keys).hasSize(1);
    assertThat(redis.getExpire(keys.get(0), TimeUnit.SECONDS)).isBetween(590L, 600L);
  }

  @Test
  void theKeyIsScopedByClientAndHashedInRedis() {
    create(KEY, "张三", "zhangsan", PASSWORD);

    String redisKey = redis.keys("uam:idem:*").iterator().next();
    assertThat(redisKey).matches("uam:idem:[0-9a-f]{64}").doesNotContain(KEY);
  }

  @Test
  void theSameKeyWithADifferentRequestIsRejectedAndNothingIsCreated() {
    create(KEY, "张三", "zhangsan", PASSWORD);

    Response response = create(KEY, "李四", "lisi.account", PASSWORD);

    assertThat(response.status()).isEqualTo(422);
    assertThat(response.json().get("code").asString()).isEqualTo("validation_failed");
    assertThat(response.json().get("errors").get(0).get("field").asString())
        .isEqualTo("Idempotency-Key");
    assertThat(response.json().get("errors").get(0).get("reason").asString()).isEqualTo("reused");
    assertThat(rows("app_user")).isEqualTo(1);
  }

  @Test
  void aRejectedAttemptDoesNotBurnTheKeySoACorrectedRetryWithTheSameKeyCreatesTheUser() {
    Response weak = create(KEY, "张三", "zhangsan", "short");
    assertThat(weak.status()).isEqualTo(422);
    assertThat(weak.json().get("errors").get(0).get("field").asString()).isEqualTo("password");
    assertThat(rows("app_user")).isZero();
    assertThat(redis.keys("uam:idem:*")).isEmpty();

    Response fixed = create(KEY, "张三", "zhangsan", PASSWORD);

    assertThat(fixed.status()).isEqualTo(201);
    assertThat(rows("app_user")).isEqualTo(1);
    assertThat(writeEvents()).hasSize(2);
    assertThat(field(writeEvents().get(0), "outcome")).isEqualTo("rejected");
    assertThat(field(writeEvents().get(0), "reason")).isEqualTo("validation_failed");
  }

  @Test
  void aMissingOrMalformedIdempotencyKeyIsAFieldLevel422() {
    Response missing = create(null, "张三", "zhangsan", PASSWORD);
    Response tooShort = create("short-key", "张三", "zhangsan", PASSWORD);

    assertThat(missing.status()).isEqualTo(422);
    assertThat(missing.json().get("errors").get(0).get("field").asString())
        .isEqualTo("Idempotency-Key");
    assertThat(missing.json().get("errors").get(0).get("reason").asString()).isEqualTo("required");
    assertThat(tooShort.status()).isEqualTo(422);
    assertThat(tooShort.json().get("errors").get(0).get("reason").asString())
        .isEqualTo("too_short");
    assertThat(rows("app_user")).isZero();
  }
}
