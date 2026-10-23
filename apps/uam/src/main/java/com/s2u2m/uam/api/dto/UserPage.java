package com.s2u2m.uam.api.dto;

import com.s2u2m.uam.application.ListUsersUseCase;
import java.util.List;

/** 用户分页（契约 {@code UserPage}）：{@code total} 为满足条件的用户总数。 */
public record UserPage(List<UserSummary> items, int page, int size, long total) {

  public static UserPage of(ListUsersUseCase.Result result) {
    return new UserPage(
        result.items().stream().map(UserSummary::of).toList(),
        result.page(),
        result.size(),
        result.total());
  }
}
