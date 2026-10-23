package com.s2u2m.uam.domain.model;

import com.s2u2m.uam.domain.policy.SessionPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 会话数据：账号与会话一一对应，会话通过账号隶属于唯一的用户。
 *
 * <p>用户名与账号名在登录时一并快照进会话，使校验只读 Redis（research R6）；二者创建后在本期都不可改。{@code expiresAt}
 * 是有效到期：空闲到期与绝对到期的较小者。
 */
public record Session(
    SessionRef ref,
    UUID userId,
    String userName,
    UUID accountId,
    String loginName,
    AccountKind kind,
    Instant loginAt,
    Instant lastActiveAt,
    Instant absoluteExpiresAt,
    Instant expiresAt) {

  /** 登录成功时开启会话；时间取毫秒精度，与 Redis 里存的一致。 */
  public static Session start(
      SessionRef ref, User user, Account account, Instant now, SessionPolicy policy) {
    Instant loginAt = now.truncatedTo(ChronoUnit.MILLIS);
    Instant absolute = policy.absoluteExpiry(loginAt);
    return new Session(
        ref,
        user.id(),
        user.name(),
        account.id(),
        account.loginName().raw(),
        account.kind(),
        loginAt,
        loginAt,
        absolute,
        policy.expiry(loginAt, absolute));
  }

  /** 续期：更新最近活动与空闲到期，绝对到期不变。 */
  public Session touched(Instant now, SessionPolicy policy) {
    Instant activeAt = now.truncatedTo(ChronoUnit.MILLIS);
    return new Session(
        ref,
        userId,
        userName,
        accountId,
        loginName,
        kind,
        loginAt,
        activeAt,
        absoluteExpiresAt,
        policy.expiry(activeAt, absoluteExpiresAt));
  }

  public boolean isActive(Instant now) {
    return now.isBefore(expiresAt);
  }
}
