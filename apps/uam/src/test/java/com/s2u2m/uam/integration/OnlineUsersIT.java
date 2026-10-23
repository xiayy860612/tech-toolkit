package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.port.SessionStore;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.SessionFixtures;
import com.s2u2m.uam.support.SessionFixtures.Created;
import com.s2u2m.uam.support.UserFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * T085：{@code GET /v1/online-users}——只含有有效会话的用户，{@code sessionCount} 与 {@code lastActiveAt}
 * 正确，按最近活动倒序； 已过期的会话不计入（{@code ZREMRANGEBYSCORE -inf now} 清理）；用户名回 PostgreSQL 取；分页。
 */
class OnlineUsersIT extends IntegrationTestBase {

  private static final String ONLINE = "uam:online";

  @Value("${local.server.port}")
  int port;

  @Autowired SessionStore store;

  private ApiClient api;
  private UserFixtures data;
  private SessionFixtures sessions;
  private Instant now;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    data = new UserFixtures(jdbc);
    sessions = new SessionFixtures(store);
    now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  private Response online(String queryString) {
    return api.get("/v1/online-users" + (queryString.isEmpty() ? "" : "?" + queryString));
  }

  private static List<UUID> userIds(Response response) {
    List<UUID> ids = new ArrayList<>();
    response
        .json()
        .get("items")
        .forEach(item -> ids.add(UUID.fromString(item.get("userId").asString())));
    return ids;
  }

  /** 新建用户、其账号与一条最近活动在 {@code ago} 之前的有效会话。 */
  private UUID onlineUser(String name, Duration ago) {
    UUID user = data.user(name);
    UUID account = data.account(user, "acct-" + UUID.randomUUID().toString().substring(0, 8));
    sessions.live(user, name, account, "any", now.minus(ago));
    return user;
  }

  @Test
  void anEmptyOnlineListIsAnEmptyPage() {
    data.user("没登录的人");

    Response response = online("");

    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.json();
    assertThat(body.get("items")).isEmpty();
    assertThat(body.get("total").asLong()).isZero();
    assertThat(body.get("page").asInt()).isEqualTo(1);
    assertThat(body.get("size").asInt()).isEqualTo(20);
  }

  @Test
  void onlyUsersWithALiveSessionAreListed() {
    UUID alice = onlineUser("爱丽丝", Duration.ofMinutes(1));
    UUID bob = onlineUser("鲍勃", Duration.ofMinutes(2));
    data.user("从未登录");
    UUID loggedOut = data.user("已退出");
    Created gone =
        sessions.live(
            loggedOut,
            "已退出",
            data.account(loggedOut, "logged.out"),
            "logged.out",
            now.minus(Duration.ofMinutes(3)));
    store.terminate(gone.ref());

    Response response = online("");

    assertThat(userIds(response)).containsExactly(alice, bob);
    assertThat(response.json().get("total").asLong()).isEqualTo(2);
  }

  @Test
  void sessionCountAndLastActiveAtAggregateTheUsersSessionsNewestFirst() {
    UUID multi = data.user("多账号用户");
    UUID first = data.account(multi, "multi.one");
    UUID second = data.account(multi, "multi.two");
    sessions.live(multi, "多账号用户", first, "multi.one", now.minus(Duration.ofSeconds(90)));
    sessions.live(multi, "多账号用户", second, "multi.two", now.minus(Duration.ofSeconds(10)));
    UUID single = onlineUser("单账号用户", Duration.ofSeconds(40));

    JsonNode items = online("").json().get("items");

    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("userId").asString()).isEqualTo(multi.toString());
    assertThat(items.get(0).get("sessionCount").asInt()).isEqualTo(2);
    assertThat(Instant.parse(items.get(0).get("lastActiveAt").asString()))
        .isEqualTo(now.minus(Duration.ofSeconds(10)));
    assertThat(items.get(1).get("userId").asString()).isEqualTo(single.toString());
    assertThat(items.get(1).get("sessionCount").asInt()).isEqualTo(1);
    assertThat(Instant.parse(items.get(1).get("lastActiveAt").asString()))
        .isEqualTo(now.minus(Duration.ofSeconds(40)));
  }

  @Test
  void usersWithTheSameLastActiveAtKeepAStableOrderByUserId() {
    Instant same = now.minus(Duration.ofMinutes(1));
    List<UUID> users = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      UUID user = data.user("同时活动" + i);
      sessions.live(user, "同时活动" + i, data.account(user, "same-" + i), "same-" + i, same);
      users.add(user);
    }

    assertThat(userIds(online(""))).containsExactlyElementsOf(users.stream().sorted().toList());
  }

  @Test
  void userNamesComeFromPostgreSqlNotFromTheSessionSnapshot() {
    UUID user = data.user("数据库里的名字");
    UUID account = data.account(user, "renamed");
    sessions.live(user, "会话快照里的旧名字", account, "renamed", now);

    JsonNode item = online("").json().get("items").get(0);

    assertThat(item.get("userName").asString()).isEqualTo("数据库里的名字");
  }

  @Test
  void aTerminatedSessionLeavesTheListAndAUserWithNoSessionDisappears() {
    UUID user = data.user("双账号");
    Created one =
        sessions.live(user, "双账号", data.account(user, "pair.one"), "pair.one", now.minusSeconds(5));
    Created two =
        sessions.live(user, "双账号", data.account(user, "pair.two"), "pair.two", now.minusSeconds(9));

    store.terminate(one.ref());
    JsonNode afterOne = online("").json().get("items");
    assertThat(afterOne).hasSize(1);
    assertThat(afterOne.get(0).get("sessionCount").asInt()).isEqualTo(1);
    assertThat(Instant.parse(afterOne.get(0).get("lastActiveAt").asString()))
        .isEqualTo(now.minusSeconds(9));

    store.terminate(two.ref());
    assertThat(online("").json().get("items")).isEmpty();
  }

  @Test
  void aSessionWhoseTtlRanOutIsNotCounted() throws InterruptedException {
    UUID user = data.user("很快到期");
    UUID account = data.account(user, "short.lived");
    sessions.expiringAt(user, "很快到期", account, "short.lived", now, Instant.now().plusMillis(1200));
    UUID stays = onlineUser("一直在线", Duration.ofSeconds(1));
    assertThat(userIds(online(""))).contains(user, stays);

    Thread.sleep(2300);

    assertThat(userIds(online(""))).containsExactly(stays);
  }

  @Test
  void expiredIndexEntriesAreSweptEvenWhenTheSessionKeysLinger() {
    UUID user = data.user("索引已过期");
    UUID account = data.account(user, "lingering");
    Created lingering = sessions.live(user, "索引已过期", account, "lingering", now);
    UUID stays = onlineUser("仍然在线", Duration.ofSeconds(1));
    // 会话键还在，但在线索引的分数已早于现在
    redis
        .opsForZSet()
        .add(ONLINE, user + "|" + lingering.ref(), Instant.now().minusSeconds(60).getEpochSecond());

    assertThat(userIds(online(""))).containsExactly(stays);
    assertThat(redis.opsForZSet().score(ONLINE, user + "|" + lingering.ref())).isNull();
    assertThat(redis.opsForZSet().zCard(ONLINE)).isEqualTo(1);
  }

  @Test
  void aSessionHashThatAlreadyExpiredIsNotCountedEvenIfTheIndexScoreIsStillInTheFuture() {
    UUID user = data.user("哈希已过期");
    UUID account = data.account(user, "stale.hash");
    Created stale = sessions.live(user, "哈希已过期", account, "stale.hash", now);
    String sha = redis.opsForValue().get("uam:sref:" + stale.ref());
    redis
        .opsForHash()
        .put("uam:sess:" + sha, "expiresAt", Long.toString(now.minusSeconds(5).toEpochMilli()));

    assertThat(userIds(online(""))).isEmpty();
  }

  @Test
  void anIndexEntryWithoutItsSessionIsNotCounted() {
    UUID ghost = data.user("没有会话的幽灵");
    redis
        .opsForZSet()
        .add(
            ONLINE,
            ghost + "|" + UUID.randomUUID(),
            Instant.now().plus(Duration.ofMinutes(10)).getEpochSecond());

    assertThat(userIds(online(""))).isEmpty();
    assertThat(online("").json().get("total").asLong()).isZero();
  }

  @Test
  void pagesTheAggregatedListAndReportsTheTotal() {
    List<UUID> byRecency = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      byRecency.add(onlineUser("在线" + i, Duration.ofMinutes(i + 1)));
    }

    Response first = online("page=1&size=2");
    Response second = online("page=2&size=2");
    Response third = online("page=3&size=2");
    Response beyond = online("page=4&size=2");

    assertThat(userIds(first)).containsExactlyElementsOf(byRecency.subList(0, 2));
    assertThat(userIds(second)).containsExactlyElementsOf(byRecency.subList(2, 4));
    assertThat(userIds(third)).containsExactlyElementsOf(byRecency.subList(4, 5));
    assertThat(userIds(beyond)).isEmpty();
    for (Response page : List.of(first, second, third, beyond)) {
      assertThat(page.json().get("total").asLong()).isEqualTo(5);
      assertThat(page.json().get("size").asInt()).isEqualTo(2);
    }
    assertThat(second.json().get("page").asInt()).isEqualTo(2);
  }

  @Test
  void pagingParametersAreValidatedLikeTheUserList() {
    for (String bad : List.of("page=0", "page=-1", "size=0", "size=101")) {
      Response response = online(bad);

      assertThat(response.status()).as(bad).isEqualTo(422);
      assertThat(response.json().get("code").asString()).isEqualTo("validation_failed");
    }
    assertThat(online("size=100").status()).isEqualTo(200);
  }

  @Test
  void aServiceKeyIsRequired() {
    Response anonymous = new ApiClient(port, null).get("/v1/online-users");

    assertThat(anonymous.status()).isEqualTo(401);
    assertThat(anonymous.json().get("code").asString()).isEqualTo("client_unauthenticated");
  }
}
