package com.s2u2m.uam.domain.model;

import java.util.UUID;

/** 凭据：「账号名 / 密码」账号的密码哈希（Argon2id，DelegatingPasswordEncoder 格式）。 */
public record Credential(UUID accountId, String passwordHash) {

  /** 不输出哈希，避免误入日志。 */
  @Override
  public String toString() {
    return "Credential[accountId=" + accountId + "]";
  }
}
