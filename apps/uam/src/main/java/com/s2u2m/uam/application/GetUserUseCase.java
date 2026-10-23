package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.port.AccountRepository;
import com.s2u2m.uam.domain.port.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 用户详情：基本信息与其关联的全部账号（FR-019、FR-020）；用户不存在抛 {@code user_not_found}。 */
@Component
@Profile("!cli")
public class GetUserUseCase {

  /** 用户及其全部账号（无账号时为空列表）。 */
  public record Result(User user, List<Account> accounts) {}

  private final UserRepository users;
  private final AccountRepository accounts;

  public GetUserUseCase(UserRepository users, AccountRepository accounts) {
    this.users = users;
    this.accounts = accounts;
  }

  public Result get(UUID userId) {
    User user =
        users
            .findById(userId)
            .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
    return new Result(user, accounts.findByUserId(userId));
  }
}
