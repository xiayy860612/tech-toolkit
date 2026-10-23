package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.LoginName;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 账号仓储；账号名按规范化名全局唯一（含已停用用户的账号）。 */
public interface AccountRepository {

  Optional<Account> findByLoginName(LoginName name);

  boolean existsByLoginName(LoginName name);

  /** 某用户的全部账号（含已停用），按创建时间升序、主键升序。 */
  List<Account> findByUserId(UUID userId);

  /** 插入账号；账号名已被使用时抛 {@code DomainException(ACCOUNT_NAME_TAKEN)}（唯一索引兜底，并发安全）。 */
  void insert(Account account);
}
