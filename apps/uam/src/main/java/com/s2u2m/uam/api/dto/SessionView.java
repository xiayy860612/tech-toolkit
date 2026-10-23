package com.s2u2m.uam.api.dto;

import com.s2u2m.uam.application.ListUserSessionsUseCase;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.Session;
import java.time.Instant;
import java.util.UUID;

/** 用户会话详情里的一条会话（契约 {@code Session}）：只含非机密的会话引用，没有会话标识（FR-031）。 */
public record SessionView(
    UUID sessionRef,
    UUID accountId,
    String accountName,
    AccountKind accountKind,
    Instant loginAt,
    Instant lastActiveAt,
    Instant expiresAt,
    boolean current) {

  public static SessionView of(ListUserSessionsUseCase.Item item) {
    Session session = item.session();
    return new SessionView(
        session.ref().value(),
        session.accountId(),
        session.loginName(),
        session.kind(),
        session.loginAt(),
        session.lastActiveAt(),
        session.expiresAt(),
        item.current());
  }
}
