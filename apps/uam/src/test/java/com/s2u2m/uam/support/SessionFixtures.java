package com.s2u2m.uam.support;

import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.SessionToken;
import com.s2u2m.uam.domain.port.SessionStore;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 集成测试造会话：经真实的 {@link SessionStore}（Redis Lua 脚本）写入会话，不经过较慢的 Argon2id 登录。
 *
 * <p>时间一律取毫秒精度，与 Redis 里存的一致；有效到期 = 最近活动 + 空闲时限，除非显式给出。
 */
public final class SessionFixtures {

  /** 一次写入的会话及其明文标识（测试用来断言它从未出现在响应与日志里）。 */
  public record Created(Session session, SessionToken token) {

    public SessionRef ref() {
      return session.ref();
    }
  }

  private static final Duration IDLE = Duration.ofMinutes(30);

  private final SessionStore store;

  public SessionFixtures(SessionStore store) {
    this.store = store;
  }

  /** 最近活动在 {@code lastActiveAt}、仍有效的会话（登录时间为其前 5 分钟）。 */
  public Created live(
      UUID userId, String userName, UUID accountId, String loginName, Instant lastActiveAt) {
    Instant active = lastActiveAt.truncatedTo(ChronoUnit.MILLIS);
    return create(userId, userName, accountId, loginName, active, active.plus(IDLE));
  }

  /** 指定有效到期的会话（用来造「很快到期」的会话）。 */
  public Created expiringAt(
      UUID userId,
      String userName,
      UUID accountId,
      String loginName,
      Instant lastActiveAt,
      Instant expiresAt) {
    return create(
        userId,
        userName,
        accountId,
        loginName,
        lastActiveAt.truncatedTo(ChronoUnit.MILLIS),
        expiresAt.truncatedTo(ChronoUnit.MILLIS));
  }

  private Created create(
      UUID userId,
      String userName,
      UUID accountId,
      String loginName,
      Instant lastActiveAt,
      Instant expiresAt) {
    Instant loginAt = lastActiveAt.minus(Duration.ofMinutes(5));
    Session session =
        new Session(
            SessionRef.random(),
            userId,
            userName,
            accountId,
            loginName,
            AccountKind.PASSWORD,
            loginAt,
            lastActiveAt,
            loginAt.plus(Duration.ofHours(12)),
            expiresAt);
    SessionToken token = SessionToken.generate();
    store.create(session, token.hash());
    return new Created(session, token);
  }
}
