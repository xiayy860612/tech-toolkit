package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.application.VerifySessionUseCase;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.SessionToken;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.policy.SessionPolicy;
import com.s2u2m.uam.infrastructure.redis.RedisSessionStore;
import com.s2u2m.uam.infrastructure.redis.ReplicaWait;
import com.s2u2m.uam.support.Fakes.FakeClock;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T048：Redis 会话存储——登录脚本原子完成「读旧 → 删旧 → 写新 → 更新索引」，只存 SHA-256，TTL 取较小者，续期只在超过 60 秒后写。 */
class RedisSessionStoreIT extends IntegrationTestBase {

  private final FakeClock clock = new FakeClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
  private final SessionPolicy policy =
      new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12), Duration.ofSeconds(60));

  private RedisSessionStore store;
  private User user;
  private Account account;

  @BeforeEach
  void setUp() {
    store = new RedisSessionStore(redis, clock, new ReplicaWait(redis));
    user = new User(UUID.randomUUID(), "张三", UserStatus.ACTIVE, clock.now());
    account =
        new Account(
            UUID.randomUUID(),
            user.id(),
            AccountKind.PASSWORD,
            LoginName.of("ZhangSan"),
            AccountStatus.ACTIVE,
            clock.now());
  }

  private Session newSession(SessionPolicy sessionPolicy) {
    return Session.start(SessionRef.random(), user, account, clock.now(), sessionPolicy);
  }

  private long pttl(String key) {
    Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
    return ttl == null ? -2 : ttl;
  }

  @Test
  void loginWritesTheSessionAndAllThreeIndexes() {
    Session session = newSession(policy);
    SessionToken token = SessionToken.generate();

    Optional<SessionRef> replaced = store.create(session, token.hash());

    assertThat(replaced).isEmpty();
    Map<Object, Object> hash = redis.opsForHash().entries("uam:sess:" + token.hash());
    assertThat(hash)
        .containsEntry("ref", session.ref().toString())
        .containsEntry("userId", user.id().toString())
        .containsEntry("userName", "张三")
        .containsEntry("accountId", account.id().toString())
        .containsEntry("loginName", "ZhangSan")
        .containsEntry("kind", "PASSWORD")
        .containsEntry("loginAt", Long.toString(session.loginAt().toEpochMilli()))
        .containsEntry("lastActiveAt", Long.toString(session.lastActiveAt().toEpochMilli()))
        .containsEntry("absExpiresAt", Long.toString(session.absoluteExpiresAt().toEpochMilli()))
        .containsEntry("expiresAt", Long.toString(session.expiresAt().toEpochMilli()));
    assertThat(redis.opsForValue().get("uam:acct:" + account.id() + ":sess"))
        .isEqualTo(token.hash());
    assertThat(redis.opsForValue().get("uam:sref:" + session.ref())).isEqualTo(token.hash());
    String member = user.id() + "|" + session.ref();
    assertThat(redis.opsForZSet().score("uam:online", member))
        .isEqualTo((double) session.expiresAt().getEpochSecond());
    assertThat(redis.opsForZSet().zCard("uam:online")).isEqualTo(1);
  }

  @Test
  void theSessionIdPlaintextIsNeverStoredOnlyItsSha256() {
    Session session = newSession(policy);
    SessionToken token = SessionToken.generate();

    store.create(session, token.hash());

    Set<String> keys = redis.keys("uam:*");
    assertThat(keys).contains("uam:sess:" + token.hash());
    assertThat(keys).noneMatch(key -> key.contains(token.id()));
    for (String key : keys) {
      String dump = String.valueOf(dumpOf(key));
      assertThat(dump).as("键 %s 的内容", key).doesNotContain(token.id());
    }
  }

  private Object dumpOf(String key) {
    return switch (String.valueOf(redis.type(key)).toLowerCase()) {
      case "hash" -> redis.opsForHash().entries(key);
      case "zset" -> redis.opsForZSet().rangeWithScores(key, 0, -1);
      default -> redis.opsForValue().get(key);
    };
  }

  @Test
  void aSecondLoginOfTheSameAccountAtomicallyReplacesTheFirst() {
    Session first = newSession(policy);
    SessionToken firstToken = SessionToken.generate();
    store.create(first, firstToken.hash());

    Session second = newSession(policy);
    SessionToken secondToken = SessionToken.generate();
    Optional<SessionRef> replaced = store.create(second, secondToken.hash());

    assertThat(replaced).contains(first.ref());
    assertThat(redis.hasKey("uam:sess:" + firstToken.hash())).isFalse();
    assertThat(redis.hasKey("uam:sref:" + first.ref())).isFalse();
    assertThat(redis.opsForZSet().score("uam:online", user.id() + "|" + first.ref())).isNull();
    assertThat(redis.opsForValue().get("uam:acct:" + account.id() + ":sess"))
        .isEqualTo(secondToken.hash());
    assertThat(redis.hasKey("uam:sess:" + secondToken.hash())).isTrue();
    assertThat(redis.opsForZSet().zCard("uam:online")).isEqualTo(1);
    assertThat(store.find(firstToken.hash())).isEmpty();
    assertThat(store.find(secondToken.hash())).isPresent();
  }

  @Test
  void differentAccountsOfTheSameUserKeepTheirOwnSessions() {
    Account other =
        new Account(
            UUID.randomUUID(),
            user.id(),
            AccountKind.PASSWORD,
            LoginName.of("second"),
            AccountStatus.ACTIVE,
            clock.now());
    Session first = newSession(policy);
    Session second = Session.start(SessionRef.random(), user, other, clock.now(), policy);

    store.create(first, SessionToken.generate().hash());
    store.create(second, SessionToken.generate().hash());

    assertThat(redis.opsForZSet().zCard("uam:online")).isEqualTo(2);
  }

  @Test
  void findRebuildsTheSessionExactly() {
    Session session = newSession(policy);
    SessionToken token = SessionToken.generate();
    store.create(session, token.hash());

    assertThat(store.find(token.hash())).contains(session);
    assertThat(store.find(SessionToken.generate().hash())).isEmpty();
  }

  @Test
  void ttlIsTheIdleRemainingWhileFarFromTheAbsoluteLimit() {
    store.create(newSession(policy), SessionToken.generate().hash());

    for (String key : redis.keys("uam:*")) {
      if (key.equals("uam:online")) {
        continue;
      }
      assertThat(pttl(key))
          .as(key)
          .isBetween(Duration.ofMinutes(29).toMillis(), Duration.ofMinutes(30).toMillis());
    }
  }

  @Test
  void ttlIsCappedByTheAbsoluteRemaining() {
    SessionPolicy shortAbsolute =
        new SessionPolicy(Duration.ofMinutes(30), Duration.ofSeconds(100), Duration.ofSeconds(60));
    Session session = newSession(shortAbsolute);
    SessionToken token = SessionToken.generate();

    store.create(session, token.hash());

    assertThat(pttl("uam:sess:" + token.hash())).isBetween(90_000L, 100_000L);
    assertThat(pttl("uam:acct:" + account.id() + ":sess")).isBetween(90_000L, 100_000L);
    assertThat(pttl("uam:sref:" + session.ref())).isBetween(90_000L, 100_000L);
    assertThat(redis.opsForZSet().score("uam:online", user.id() + "|" + session.ref()))
        .isEqualTo((double) session.absoluteExpiresAt().getEpochSecond());
  }

  @Test
  void renewalWritesOnlyAfterMoreThanSixtySeconds() {
    VerifySessionUseCase verify = new VerifySessionUseCase(store, policy, clock);
    Session session = newSession(policy);
    SessionToken token = SessionToken.generate();
    store.create(session, token.hash());
    String key = "uam:sess:" + token.hash();

    clock.advance(Duration.ofSeconds(30));
    verify.verify(token.id());
    assertThat(redis.opsForHash().get(key, "lastActiveAt"))
        .as("60 秒内不写")
        .isEqualTo(Long.toString(session.lastActiveAt().toEpochMilli()));

    clock.advance(Duration.ofSeconds(31));
    Session renewed = verify.verify(token.id());

    assertThat(renewed.lastActiveAt()).isEqualTo(clock.now());
    assertThat(redis.opsForHash().get(key, "lastActiveAt"))
        .isEqualTo(Long.toString(clock.now().toEpochMilli()));
    assertThat(redis.opsForHash().get(key, "expiresAt"))
        .isEqualTo(Long.toString(clock.now().plus(Duration.ofMinutes(30)).toEpochMilli()));
    assertThat(redis.opsForZSet().score("uam:online", user.id() + "|" + session.ref()))
        .isEqualTo((double) clock.now().plus(Duration.ofMinutes(30)).getEpochSecond());
    // 三个键的 TTL 一起刷新，不会出现会话还在、索引先过期
    for (String indexKey :
        new String[] {key, "uam:acct:" + account.id() + ":sess", "uam:sref:" + session.ref()}) {
      assertThat(pttl(indexKey)).as(indexKey).isGreaterThan(Duration.ofMinutes(29).toMillis());
    }
  }

  @Test
  void touchingAVanishedSessionIsEmptyAndRecreatesNothing() {
    Session session = newSession(policy);
    SessionToken token = SessionToken.generate();

    Optional<Session> result = store.touch(token.hash(), session.touched(clock.now(), policy));

    assertThat(result).isEmpty();
    assertThat(redis.keys("uam:*")).isEmpty();
  }

  @Test
  void terminateRemovesTheSessionAndAllIndexes() {
    Session session = newSession(policy);
    SessionToken token = SessionToken.generate();
    store.create(session, token.hash());

    assertThat(store.terminate(session.ref())).isTrue();

    assertThat(redis.keys("uam:*")).isEmpty();
    assertThat(store.find(token.hash())).isEmpty();
    assertThat(store.terminate(session.ref())).as("重复终止").isFalse();
  }

  @Test
  void terminateKeepsTheAccountIndexWhenItAlreadyPointsToALaterSession() {
    Session old = newSession(policy);
    store.create(old, SessionToken.generate().hash());
    String accountKey = "uam:acct:" + account.id() + ":sess";
    redis.opsForValue().set(accountKey, "later-session-hash");

    assertThat(store.terminate(old.ref())).isTrue();

    assertThat(redis.opsForValue().get(accountKey)).isEqualTo("later-session-hash");
    assertThat(redis.hasKey("uam:sref:" + old.ref())).isFalse();
  }

  private Account secondAccount(User owner, String loginName) {
    return new Account(
        UUID.randomUUID(),
        owner.id(),
        AccountKind.PASSWORD,
        LoginName.of(loginName),
        AccountStatus.ACTIVE,
        clock.now());
  }

  @Test
  void terminateAllForUserClearsEveryIndexOfEveryAccountAndLeavesOthersAlone() {
    Account second = secondAccount(user, "ZhangSan2");
    Account noSession = secondAccount(user, "ZhangSan3");
    SessionToken t1 = SessionToken.generate();
    SessionToken t2 = SessionToken.generate();
    Session s1 = newSession(policy);
    Session s2 = Session.start(SessionRef.random(), user, second, clock.now(), policy);
    store.create(s1, t1.hash());
    store.create(s2, t2.hash());
    User other = new User(UUID.randomUUID(), "李四", UserStatus.ACTIVE, clock.now());
    Account otherAccount = secondAccount(other, "LiSi");
    Session otherSession =
        Session.start(SessionRef.random(), other, otherAccount, clock.now(), policy);
    SessionToken otherToken = SessionToken.generate();
    store.create(otherSession, otherToken.hash());

    int terminated = store.terminateAllForUser(List.of(account.id(), second.id(), noSession.id()));

    assertThat(terminated).isEqualTo(2);
    assertThat(store.find(t1.hash())).isEmpty();
    assertThat(store.find(t2.hash())).isEmpty();
    assertThat(store.find(otherToken.hash())).isPresent();
    assertThat(redis.keys("uam:*"))
        .containsExactlyInAnyOrder(
            "uam:sess:" + otherToken.hash(),
            "uam:sref:" + otherSession.ref(),
            "uam:acct:" + otherAccount.id() + ":sess",
            "uam:online");
    assertThat(redis.opsForZSet().range("uam:online", 0, -1))
        .containsExactly(other.id() + "|" + otherSession.ref());
  }

  @Test
  void terminateAllForUserIsANoOpWithoutSessions() {
    assertThat(store.terminateAllForUser(List.of(account.id()))).isZero();
    assertThat(store.terminateAllForUser(List.of())).isZero();
    assertThat(redis.keys("uam:*")).isEmpty();
  }
}
