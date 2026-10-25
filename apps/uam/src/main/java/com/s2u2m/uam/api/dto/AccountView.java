package com.s2u2m.uam.api.dto;

import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import java.time.Instant;
import java.util.UUID;

/** 用户详情里的账号（契约 {@code Account}）：不含任何凭据。 */
public record AccountView(
    UUID id, String loginName, AccountKind kind, AccountStatus status, Instant createdAt) {

  public static AccountView of(Account account) {
    return new AccountView(
        account.id(),
        account.loginName().raw(),
        account.kind(),
        account.status(),
        account.createdAt());
  }
}
