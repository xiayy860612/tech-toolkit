package com.s2u2m.uam.domain.model;

import java.time.Instant;
import java.util.UUID;

/** 账号：隶属于唯一的用户；账号名创建后不可改。 */
public record Account(
    UUID id,
    UUID userId,
    AccountKind kind,
    LoginName loginName,
    AccountStatus status,
    Instant createdAt) {

  /** 能否登录：账号 {@code ACTIVE} 且所属用户 {@code ACTIVE}（启用用户不改变账号自身状态）。 */
  public boolean canLogin(User owner) {
    return status == AccountStatus.ACTIVE && owner.id().equals(userId) && owner.isActive();
  }
}
