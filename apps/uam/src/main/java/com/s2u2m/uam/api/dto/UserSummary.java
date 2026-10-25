package com.s2u2m.uam.api.dto;

import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.model.UserWithAccountCount;
import java.time.Instant;
import java.util.UUID;

/** 列表里的用户（契约 {@code UserSummary}）。 */
public record UserSummary(
    UUID id, String name, UserStatus status, long accountCount, Instant createdAt) {

  public static UserSummary of(UserWithAccountCount row) {
    var user = row.user();
    return new UserSummary(
        user.id(), user.name(), user.status(), row.accountCount(), user.createdAt());
  }
}
