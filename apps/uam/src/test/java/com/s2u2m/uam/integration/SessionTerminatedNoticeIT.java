package com.s2u2m.uam.integration;

import static com.s2u2m.uam.support.OpenApiContract.assertConforms;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.s2u2m.uam.domain.model.SessionToken;
import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.UserFixtures;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * T194：「被管理员强制下线」专属提示的端到端行为（真实 PostgreSQL 与 Redis；通知窗口调到 3 秒以便测到过期）。
 *
 * <p>只有管理员强制下线（{@code DELETE /v1/sessions/{ref}}）在通知窗口内让 {@code verify} 的 401 {@code
 * session_invalid} 带 {@code reason: terminated}；退出、停用、被新登录取代都不带 {@code reason}；墓碑只含会话标识的哈希，按 TTL
 * 过期，响应与日志里没有会话标识。
 */
@TestPropertySource(properties = "uam.session.terminated-notice-ttl=PT3S")
class SessionTerminatedNoticeIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final String IP = "203.0.113.7";
  private static final Duration NOTICE_TTL = Duration.ofSeconds(3);

  @Value("${local.server.port}")
  int port;

  @org.springframework.beans.factory.annotation.Autowired PasswordHasher hasher;

  private ApiClient api;
  private UUID operatorUser;
  private UUID operatorAccount;
  private UUID target;
  private UUID targetAccount;

  private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  private record Login(String sessionId, UUID ref) {}

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    UserFixtures data = new UserFixtures(jdbc);
    operatorUser = data.user("运维甲");
    operatorAccount = data.account(operatorUser, "ops");
    data.credential(operatorAccount, hasher.hash(PASSWORD));
    target = data.user("张三");
    targetAccount = data.account(target, "zhangsan");
    data.credential(targetAccount, hasher.hash(PASSWORD));
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

  private Map<String, String> operator() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Operator-User-Id", operatorUser.toString());
    headers.put("X-Operator-Account-Id", operatorAccount.toString());
    headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
    return headers;
  }

  private Response terminate(UUID ref) {
    return api.delete("/v1/sessions/" + ref, operator());
  }

  private static String tombstoneKey(String sessionId) {
    return "uam:term:" + SessionToken.hashOf(sessionId);
  }

  /** 401 {@code session_invalid}，且 body 恰好是契约允许的字段；{@code reason} 为期望值或不存在。 */
  private JsonNode assertSessionInvalid(Response response, String expectedReason) {
    assertThat(response.status()).isEqualTo(401);
    assertThat(response.contentType()).startsWith("application/problem+json");
    JsonNode body = response.json();
    assertConforms("Problem", body);
    assertThat(body.get("code").asString()).isEqualTo("session_invalid");
    if (expectedReason == null) {
      assertThat(body.has("reason")).as("不带 reason").isFalse();
    } else {
      assertThat(body.get("reason").asString()).isEqualTo(expectedReason);
    }
    return body;
  }

  @Test
  void afterAnAdministratorForceTerminateVerifyIs401SessionInvalidWithReasonTerminated() {
    Login victim = login("zhangsan");

    assertThat(terminate(victim.ref()).status()).isEqualTo(204);

    Response verify = api.verify(victim.sessionId());
    JsonNode body = assertSessionInvalid(verify, "terminated");
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder("type", "title", "status", "code", "detail", "reason");
    assertThat(api.verify(victim.sessionId()).json().get("reason").asString())
        .as("通知窗口内可重复读到，不是一次性")
        .isEqualTo("terminated");
  }

  @Test
  void theTombstoneHoldsOnlyTheHashWithTheConfiguredTtlAndNoSessionIdentifierLeaks() {
    Login victim = login("zhangsan");

    Response terminate = terminate(victim.ref());
    Response verify = api.verify(victim.sessionId());

    String key = tombstoneKey(victim.sessionId());
    assertThat(redis.hasKey(key)).isTrue();
    assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS))
        .as("TTL = uam.session.terminated-notice-ttl")
        .isPositive()
        .isLessThanOrEqualTo(NOTICE_TTL.toMillis());
    Set<String> keys = redis.keys("uam:*");
    assertThat(keys).noneMatch(k -> k.contains(victim.sessionId()));
    assertThat(keys).contains(key).doesNotContain("uam:sref:" + victim.ref());
    assertThat(terminate.body() + verify.body()).doesNotContain(victim.sessionId());
    assertThat(logs.list)
        .noneMatch(
            e ->
                e.getFormattedMessage().contains(victim.sessionId())
                    || e.getKeyValuePairs().stream()
                        .anyMatch(kv -> String.valueOf(kv.value).contains(victim.sessionId())));
  }

  @Test
  void theNoticeDisappearsAfterTheTtlAndTheSessionStaysDead() throws InterruptedException {
    Login victim = login("zhangsan");
    terminate(victim.ref());
    assertSessionInvalid(api.verify(victim.sessionId()), "terminated");

    long deadline = System.currentTimeMillis() + NOTICE_TTL.toMillis() + 5_000;
    while (Boolean.TRUE.equals(redis.hasKey(tombstoneKey(victim.sessionId())))
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }

    assertThat(redis.hasKey(tombstoneKey(victim.sessionId()))).as("墓碑已过期").isFalse();
    assertSessionInvalid(api.verify(victim.sessionId()), null);
  }

  @Test
  void logoutLeavesNoTombstoneAndVerifyHasNoReason() {
    Login own = login("zhangsan");

    assertThat(api.logout(own.sessionId()).status()).isEqualTo(204);

    assertSessionInvalid(api.verify(own.sessionId()), null);
    assertThat(redis.keys("uam:term:*")).isEmpty();
  }

  @Test
  void disablingTheUserLeavesNoTombstoneAndVerifyHasNoReason() {
    Login victim = login("zhangsan");

    Response disable = api.postJson("/v1/users/" + target + "/disable", Map.of(), operator());

    assertThat(disable.status()).isEqualTo(200);
    assertSessionInvalid(api.verify(victim.sessionId()), null);
    assertThat(redis.keys("uam:term:*")).isEmpty();
  }

  @Test
  void aSessionSupersededByANewLoginHasNoReason() {
    Login first = login("zhangsan");
    Login second = login("zhangsan");

    assertSessionInvalid(api.verify(first.sessionId()), null);
    assertThat(api.verify(second.sessionId()).status()).isEqualTo(200);
    assertThat(redis.keys("uam:term:*")).isEmpty();
  }

  @Test
  void aNewLoginAfterTheForceTerminateWorksAndTheOldIdStaysDeadWithItsNotice() {
    Login victim = login("zhangsan");
    terminate(victim.ref());

    Login again = login("zhangsan");

    assertThat(api.verify(again.sessionId()).status()).isEqualTo(200);
    assertSessionInvalid(api.verify(victim.sessionId()), "terminated");
  }

  @Test
  void unknownIdsAndOtherSessionsAreNotAffectedByAnotherSessionsTombstone() {
    Login victim = login("zhangsan");
    Login stranger = login("ops");
    terminate(victim.ref());

    assertSessionInvalid(api.verify(SessionToken.generate().id()), null);
    assertThat(api.verify(stranger.sessionId()).status()).isEqualTo(200);
    assertThat(List.copyOf(redis.keys("uam:term:*")))
        .containsExactly(tombstoneKey(victim.sessionId()));
  }
}
