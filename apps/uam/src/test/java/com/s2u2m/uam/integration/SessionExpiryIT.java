package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * T144 / T149：会话自然失效（FR-010、FR-011，research R8）。时限调到秒级（空闲 3 秒、绝对 6 秒、续期间隔 0.3 秒）：
 *
 * <ul>
 *   <li>空闲到期：不再有活动，到期后 {@code verify} → 401 {@code session_invalid}，会话从在线用户与用户会话中消失；
 *   <li>绝对到期：持续有活动（空闲永不到期）也在绝对时限到达时失效；
 *   <li>Redis TTL（三个会话键）取空闲剩余与绝对剩余的较小值，{@code uam:online} 分数同步为有效到期。
 * </ul>
 *
 * 全部以轮询 Redis 或带截止时间的循环断言，不靠固定的长睡眠；轮询 {@code verify} 会续期，所以空闲到期用例只轮询 Redis。
 */
@TestPropertySource(
    properties = {
      "uam.session.idle=PT3S",
      "uam.session.absolute=PT6S",
      "uam.session.touch-interval=PT0.3S"
    })
class SessionExpiryIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final Duration IDLE = Duration.ofSeconds(3);
  private static final Duration ABSOLUTE = Duration.ofSeconds(6);

  /** 计时与调度的容差。 */
  private static final Duration SLACK = Duration.ofMillis(1500);

  @Value("${local.server.port}")
  int port;

  @Autowired AccountProvisioningService provisioning;

  private ApiClient api;
  private String userId;

  /** 一次登录：会话标识、会话引用、登录时刻（取自响应，避免依赖测试自己的时钟）。 */
  private record Login(String sessionId, String ref, Instant loggedInAt) {}

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    userId = provisioning.createUserWithAccount("爱丽丝", "Alice", PASSWORD).userId().toString();
  }

  private Login login() {
    Instant before = Instant.now();
    Response response = api.login("alice", PASSWORD, "203.0.113.7");
    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.json();
    return new Login(body.get("sessionId").asString(), body.get("sessionRef").asString(), before);
  }

  private String sessionKey(Login login) {
    String sha = redis.opsForValue().get("uam:sref:" + login.ref());
    return sha == null ? null : "uam:sess:" + sha;
  }

  private boolean sessionKeyExists(Login login) {
    return Boolean.TRUE.equals(redis.hasKey("uam:sref:" + login.ref()));
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private void assertSessionInvalid(Response response) {
    assertThat(response.status()).isEqualTo(401);
    assertThat(response.contentType()).startsWith("application/problem+json");
    assertThat(response.json().get("code").asString()).isEqualTo("session_invalid");
    assertThat(response.json().has("reason")).as("到期不带 reason").isFalse();
  }

  private void assertNotOnlineAnywhere() {
    Response online = api.get("/v1/online-users");
    assertThat(online.status()).isEqualTo(200);
    assertThat(online.json().get("items")).isEmpty();
    assertThat(online.json().get("total").asLong()).isZero();
    assertThat(redis.opsForZSet().zCard("uam:online")).as("到期的在线索引条目已清理").isZero();
    Response sessions = api.get("/v1/users/" + userId + "/sessions");
    assertThat(sessions.status()).isEqualTo(200);
    assertThat(sessions.json().get("items")).isEmpty();
  }

  @Test
  void anIdleSessionExpiresAfterTheIdleLimit() {
    Login login = login();
    Instant deadline = login.loggedInAt().plus(IDLE).plus(SLACK).plus(SLACK);
    // 只读 Redis（不经 verify），否则会续期
    while (sessionKeyExists(login) && Instant.now().isBefore(deadline)) {
      sleep(Duration.ofMillis(100));
    }
    Instant expiredAt = Instant.now();

    assertThat(sessionKeyExists(login)).as("空闲到期后会话键消失").isFalse();
    assertThat(Duration.between(login.loggedInAt(), expiredAt))
        .as("不会早于空闲时限到期")
        .isGreaterThanOrEqualTo(IDLE.minus(Duration.ofMillis(500)));
    assertSessionInvalid(api.verify(login.sessionId()));
    assertNotOnlineAnywhere();
  }

  @Test
  void aSessionWithActivityOutlivesTheIdleLimitButNotTheAbsoluteLimit() {
    Login login = login();
    Instant absoluteDeadline = login.loggedInAt().plus(ABSOLUTE);
    Instant giveUpAt = absoluteDeadline.plus(SLACK).plus(SLACK);
    Instant lastValidAt = null;
    Instant firstInvalidAt = null;
    while (firstInvalidAt == null && Instant.now().isBefore(giveUpAt)) {
      Instant at = Instant.now();
      Response response = api.verify(login.sessionId());
      if (response.status() == 200) {
        lastValidAt = at;
      } else {
        assertSessionInvalid(response);
        firstInvalidAt = at;
      }
      sleep(Duration.ofMillis(400));
    }

    assertThat(firstInvalidAt).as("绝对时限到达后会话失效").isNotNull();
    assertThat(lastValidAt).isNotNull();
    assertThat(Duration.between(login.loggedInAt(), lastValidAt))
        .as("持续活动让会话活过空闲时限")
        .isGreaterThan(IDLE);
    assertThat(Duration.between(login.loggedInAt(), firstInvalidAt))
        .as("不会早于绝对时限失效")
        .isGreaterThanOrEqualTo(ABSOLUTE.minus(Duration.ofMillis(500)));
    assertSessionInvalid(api.verify(login.sessionId()));
    assertNotOnlineAnywhere();
  }

  @Test
  void ttlIsTheSmallerOfIdleAndAbsoluteRemainingAndTheOnlineScoreFollows() {
    Login login = login();
    String key = sessionKey(login);
    Map<Object, Object> fresh = redis.opsForHash().entries(key);
    long absExpiresAt = Long.parseLong((String) fresh.get("absExpiresAt"));
    long freshExpiresAt = Long.parseLong((String) fresh.get("expiresAt"));
    long freshLastActive = Long.parseLong((String) fresh.get("lastActiveAt"));
    assertThat(freshExpiresAt).as("刚登录：空闲剩余更小").isEqualTo(freshLastActive + IDLE.toMillis());
    assertThat(freshExpiresAt).isLessThan(absExpiresAt);
    assertThat(redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS))
        .isBetween(1L, IDLE.toMillis());

    // 持续活动，直到最近活动晚于「绝对到期 - 空闲」，此后绝对剩余更小
    long idleStopsMattering = absExpiresAt - IDLE.toMillis();
    long lastActive = freshLastActive;
    Instant giveUpAt = login.loggedInAt().plus(ABSOLUTE).minus(Duration.ofMillis(800));
    while (lastActive <= idleStopsMattering && Instant.now().isBefore(giveUpAt)) {
      sleep(Duration.ofMillis(400));
      assertThat(api.verify(login.sessionId()).status()).isEqualTo(200);
      lastActive = Long.parseLong((String) redis.opsForHash().get(key, "lastActiveAt"));
    }
    assertThat(lastActive).as("在绝对到期前完成了一次晚期续期").isGreaterThan(idleStopsMattering);

    Map<Object, Object> late = redis.opsForHash().entries(key);
    long expiresAt = Long.parseLong((String) late.get("expiresAt"));
    assertThat(expiresAt).as("晚期续期后有效到期 = 绝对到期").isEqualTo(absExpiresAt);
    assertThat(expiresAt).isLessThan(lastActive + IDLE.toMillis());
    long remaining = absExpiresAt - Instant.now().toEpochMilli();
    for (String ttlKey :
        new String[] {
          key, "uam:sref:" + login.ref(), "uam:acct:" + late.get("accountId") + ":sess"
        }) {
      Long ttl = redis.getExpire(ttlKey, java.util.concurrent.TimeUnit.MILLISECONDS);
      assertThat(ttl)
          .as("%s 的 TTL 取绝对剩余，而不是更大的空闲剩余", ttlKey)
          .isPositive()
          .isLessThanOrEqualTo(remaining + 50);
    }
    Double score = redis.opsForZSet().score("uam:online", userId + "|" + login.ref());
    assertThat(score).as("online 分数 = 有效到期（epoch 秒）").isEqualTo((double) (expiresAt / 1000));
  }
}
