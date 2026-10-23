package com.s2u2m.uam.api.dto;

import com.s2u2m.uam.application.ListOnlineUsersUseCase;
import java.time.Instant;
import java.util.UUID;

/** 在线用户列表里的一行（契约 {@code OnlineUser}）。 */
public record OnlineUser(UUID userId, String userName, int sessionCount, Instant lastActiveAt) {

  public static OnlineUser of(ListOnlineUsersUseCase.Item item) {
    return new OnlineUser(item.userId(), item.userName(), item.sessionCount(), item.lastActiveAt());
  }
}
