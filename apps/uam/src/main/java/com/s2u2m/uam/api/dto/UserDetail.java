package com.s2u2m.uam.api.dto;

import com.s2u2m.uam.application.GetUserUseCase;
import com.s2u2m.uam.domain.model.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 用户详情（契约 {@code UserDetail}）：基本信息与全部账号，无账号时 {@code accounts} 为空数组。 */
public record UserDetail(
    UUID id, String name, UserStatus status, Instant createdAt, List<AccountView> accounts) {

  public static UserDetail of(GetUserUseCase.Result result) {
    var user = result.user();
    return new UserDetail(
        user.id(),
        user.name(),
        user.status(),
        user.createdAt(),
        result.accounts().stream().map(AccountView::of).toList());
  }
}
