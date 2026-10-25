package com.s2u2m.uam.api.dto;

import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.Session;
import java.time.Instant;
import java.util.UUID;

/** 会话数据（契约 {@code VerifyResponse}）：用户、账号、会话引用与到期时间；不含会话标识。 */
public record VerifyResponse(
    SessionUser user, SessionAccount account, UUID sessionRef, Instant expiresAt) {

  /** 契约 {@code SessionUser}。 */
  public record SessionUser(UUID id, String name) {}

  /** 契约 {@code SessionAccount}。 */
  public record SessionAccount(UUID id, String loginName, AccountKind kind) {}

  public static VerifyResponse of(Session session) {
    return new VerifyResponse(
        new SessionUser(session.userId(), session.userName()),
        new SessionAccount(session.accountId(), session.loginName(), session.kind()),
        session.ref().value(),
        session.expiresAt());
  }
}
