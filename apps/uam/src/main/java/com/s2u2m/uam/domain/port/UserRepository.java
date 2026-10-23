package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.PageResult;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserWithAccountCount;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 用户仓储（PostgreSQL，强一致）。 */
public interface UserRepository {

  Optional<User> findById(UUID id);

  void insert(User user);

  /** 全部用户数（含已停用）。 */
  long count();

  /**
   * 分页列出用户（含已停用）并带上每个用户的账号总数，按创建时间倒序、主键升序，保证分页稳定。
   *
   * @param query 已去首尾空白的搜索词，{@code null} 表示不搜索；大小写不敏感、包含匹配用户名与账号名，{@code % _ \} 按字面匹配
   * @param page 页码，从 1 开始
   * @param size 每页条数
   */
  PageResult<UserWithAccountCount> search(String query, int page, int size);

  /** 一次批量取用户名；不存在的用户不出现在结果里。 */
  Map<UUID, String> findNames(Collection<UUID> ids);
}
