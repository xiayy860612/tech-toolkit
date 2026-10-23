package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.LoginName;
import java.util.Optional;

/** 账号仓储；账号名按规范化名全局唯一（含已停用用户的账号）。 */
public interface AccountRepository {

  Optional<Account> findByLoginName(LoginName name);

  boolean existsByLoginName(LoginName name);

  /** 插入账号；账号名已被使用时抛 {@code DomainException(ACCOUNT_NAME_TAKEN)}（唯一索引兜底，并发安全）。 */
  void insert(Account account);
}
