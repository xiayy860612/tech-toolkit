package com.s2u2m.uam.api.dto;

import com.s2u2m.uam.application.ListOnlineUsersUseCase;
import java.util.List;

/** 在线用户分页（契约 {@code OnlineUserPage}）：{@code total} 为在线用户总数。 */
public record OnlineUserPage(List<OnlineUser> items, int page, int size, long total) {

  public static OnlineUserPage of(ListOnlineUsersUseCase.Result result) {
    return new OnlineUserPage(
        result.items().stream().map(OnlineUser::of).toList(),
        result.page(),
        result.size(),
        result.total());
  }
}
