package com.s2u2m.uam.domain.model;

import java.time.Instant;
import java.util.UUID;

/** 用户：1 个用户可有多个账号；名称不要求唯一。 */
public record User(UUID id, String name, UserStatus status, Instant createdAt) {

  public boolean isActive() {
    return status == UserStatus.ACTIVE;
  }
}
