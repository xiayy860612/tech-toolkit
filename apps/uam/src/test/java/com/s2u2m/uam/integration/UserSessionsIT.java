package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.port.SessionStore;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.OpenApiContract;
import com.s2u2m.uam.support.SessionFixtures;
import com.s2u2m.uam.support.SessionFixtures.Created;
import com.s2u2m.uam.support.UserFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * T086：{@code GET /v1/users/{userId}/sessions}——某用户的会话 = 其每个账号 {@code uam:acct:{accountId}:sess}
 * 指向的会话；每条含账号名与种类、 登录 / 最近活动 / 到期时间；{@code current} 由 {@code X-Operator-Session-Ref}
 * 得出；响应与日志里没有会话标识。
 */
@ExtendWith(OutputCaptureExtension.class)
class UserSessionsIT extends IntegrationTestBase {

  @Value("${local.server.port}")
  int port;

  @Autowired SessionStore store;

  private ApiClient api;
  private UserFixtures data;
  private SessionFixtures sessions;
  private Instant now;

  private UUID user;
  private UUID firstAccount;
  private UUID secondAccount;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    data = new UserFixtures(jdbc);
    sessions = new SessionFixtures(store);
    now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    user = data.user("多账号用户");
    firstAccount = data.account(user, "Multi.One", "ACTIVE", now.minusSeconds(100));
    secondAccount = data.account(user, "multi.two", "ACTIVE", now.minusSeconds(50));
  }

  private Response sessionsOf(UUID userId) {
    return api.get("/v1/users/" + userId + "/sessions");
  }

  private Response sessionsOf(UUID userId, String operatorSessionRef) {
    return api.get(
        "/v1/users/" + userId + "/sessions", Map.of("X-Operator-Session-Ref", operatorSessionRef));
  }

  @Test
  void eachAccountContributesTheSessionItsPointerNamesWithAccountAndTimes() {
    Created one = sessions.live(user, "多账号用户", firstAccount, "Multi.One", now.minusSeconds(30));
    Created two = sessions.live(user, "多账号用户", secondAccount, "multi.two", now.minusSeconds(5));

    Response response = sessionsOf(user);

    assertThat(response.status()).isEqualTo(200);
    JsonNode items = response.json().get("items");
    assertThat(items).hasSize(2);
    JsonNode first = items.get(0);
    assertThat(first.get("sessionRef").asString()).isEqualTo(one.ref().toString());
    assertThat(first.get("accountId").asString()).isEqualTo(firstAccount.toString());
    assertThat(first.get("accountName").asString()).isEqualTo("Multi.One");
    assertThat(first.get("accountKind").asString()).isEqualTo("PASSWORD");
    assertThat(Instant.parse(first.get("loginAt").asString())).isEqualTo(one.session().loginAt());
    assertThat(Instant.parse(first.get("lastActiveAt").asString()))
        .isEqualTo(one.session().lastActiveAt());
    assertThat(Instant.parse(first.get("expiresAt").asString()))
        .isEqualTo(one.session().expiresAt());
    assertThat(items.get(1).get("sessionRef").asString()).isEqualTo(two.ref().toString());
    assertThat(items.get(1).get("accountName").asString()).isEqualTo("multi.two");
  }

  @Test
  void aReplacedSessionIsNotListedOnlyTheOneTheAccountPointerNamesIs() {
    Created replaced =
        sessions.live(user, "多账号用户", firstAccount, "Multi.One", now.minusSeconds(60));
    Created current = sessions.live(user, "多账号用户", firstAccount, "Multi.One", now.minusSeconds(2));

    JsonNode items = sessionsOf(user).json().get("items");

    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("sessionRef").asString()).isEqualTo(current.ref().toString());
    assertThat(items.toString()).doesNotContain(replaced.ref().toString());
  }

  @Test
  void anAccountWithoutASessionAndOtherUsersSessionsAreNotListed() {
    Created mine = sessions.live(user, "多账号用户", secondAccount, "multi.two", now);
    UUID other = data.user("别人");
    UUID otherAccount = data.account(other, "someone.else");
    Created theirs = sessions.live(other, "别人", otherAccount, "someone.else", now);

    JsonNode items = sessionsOf(user).json().get("items");

    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("sessionRef").asString()).isEqualTo(mine.ref().toString());
    assertThat(items.toString()).doesNotContain(theirs.ref().toString());
  }

  @Test
  void aUserWithoutAnySessionHasAnEmptyList() {
    Response response = sessionsOf(user);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().get("items")).isEmpty();
  }

  @Test
  void aUserWithoutAccountsHasAnEmptyList() {
    UUID bare = data.user("无账号用户");

    assertThat(sessionsOf(bare).json().get("items")).isEmpty();
  }

  @Test
  void anUnknownUserIsNotFound() {
    Response response = sessionsOf(UUID.randomUUID());

    assertThat(response.status()).isEqualTo(404);
    assertThat(response.contentType()).startsWith("application/problem+json");
    assertThat(response.json().get("code").asString()).isEqualTo("user_not_found");
  }

  @Test
  void aMalformedUserIdIsABadRequest() {
    assertThat(api.get("/v1/users/not-a-uuid/sessions").status()).isEqualTo(400);
  }

  @Test
  void currentIsTrueOnlyForTheSessionNamedByTheOperatorHeader() {
    Created one = sessions.live(user, "多账号用户", firstAccount, "Multi.One", now.minusSeconds(30));
    Created two = sessions.live(user, "多账号用户", secondAccount, "multi.two", now.minusSeconds(5));

    JsonNode withHeader = sessionsOf(user, two.ref().toString()).json().get("items");

    assertThat(withHeader.get(0).get("sessionRef").asString()).isEqualTo(one.ref().toString());
    assertThat(withHeader.get(0).get("current").asBoolean()).isFalse();
    assertThat(withHeader.get(1).get("current").asBoolean()).isTrue();
  }

  @Test
  void currentIsFalseWithoutTheHeaderOrWithAnUnrelatedRef() {
    sessions.live(user, "多账号用户", firstAccount, "Multi.One", now.minusSeconds(30));
    sessions.live(user, "多账号用户", secondAccount, "multi.two", now.minusSeconds(5));

    for (Response response :
        List.of(sessionsOf(user), sessionsOf(user, UUID.randomUUID().toString()))) {
      assertThat(response.status()).isEqualTo(200);
      response
          .json()
          .get("items")
          .forEach(item -> assertThat(item.get("current").asBoolean()).isFalse());
    }
  }

  @Test
  void aMalformedOperatorSessionRefIsRejectedAsOperatorRequired() {
    Response response = sessionsOf(user, "not-a-uuid");

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("code").asString()).isEqualTo("operator_required");
  }

  @Test
  void theResponseAndTheLogsNeverCarryTheSessionIdentifier(CapturedOutput output) {
    Created one = sessions.live(user, "多账号用户", firstAccount, "Multi.One", now.minusSeconds(30));
    Created two = sessions.live(user, "多账号用户", secondAccount, "multi.two", now.minusSeconds(5));

    Response response = sessionsOf(user, two.ref().toString());
    Response online = api.get("/v1/online-users");

    assertThat(OpenApiContract.fieldNames(response.json()))
        .doesNotContain("sessionId", "token", "tokenHash", "sha", "hash");
    for (Created created : List.of(one, two)) {
      for (String secret : List.of(created.token().id(), created.token().hash())) {
        assertThat(response.body()).doesNotContain(secret);
        assertThat(online.body()).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(secret);
      }
    }
  }

  @Test
  void aSessionWhoseTtlRanOutIsNotListed() throws InterruptedException {
    sessions.expiringAt(
        user, "多账号用户", firstAccount, "Multi.One", now, Instant.now().plusMillis(1200));
    Created stays = sessions.live(user, "多账号用户", secondAccount, "multi.two", now);
    assertThat(sessionsOf(user).json().get("items")).hasSize(2);

    Thread.sleep(2300);

    JsonNode items = sessionsOf(user).json().get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("sessionRef").asString()).isEqualTo(stays.ref().toString());
  }

  @Test
  void aSessionThatAlreadyExpiredIsNotListedEvenIfItsKeysLinger() {
    Created lingering = sessions.live(user, "多账号用户", firstAccount, "Multi.One", now);
    String sha = redis.opsForValue().get("uam:sref:" + lingering.ref());
    redis
        .opsForHash()
        .put(
            "uam:sess:" + sha,
            "expiresAt",
            Long.toString(now.minus(Duration.ofSeconds(5)).toEpochMilli()));

    assertThat(sessionsOf(user).json().get("items")).isEmpty();
  }

  @Test
  void aTerminatedSessionIsGoneOnTheNextRead() {
    Created one = sessions.live(user, "多账号用户", firstAccount, "Multi.One", now);
    assertThat(sessionsOf(user).json().get("items")).hasSize(1);

    store.terminate(one.ref());

    assertThat(sessionsOf(user).json().get("items")).isEmpty();
  }

  @Test
  void aServiceKeyIsRequired() {
    Response anonymous = new ApiClient(port, null).get("/v1/users/" + user + "/sessions");

    assertThat(anonymous.status()).isEqualTo(401);
    assertThat(anonymous.json().get("code").asString()).isEqualTo("client_unauthenticated");
  }
}
