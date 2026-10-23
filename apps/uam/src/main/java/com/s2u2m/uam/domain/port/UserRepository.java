package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.User;
import java.util.Optional;
import java.util.UUID;

/** 用户仓储（PostgreSQL，强一致）。 */
public interface UserRepository {

  Optional<User> findById(UUID id);

  void insert(User user);

  /** 全部用户数（含已停用）。 */
  long count();
}
