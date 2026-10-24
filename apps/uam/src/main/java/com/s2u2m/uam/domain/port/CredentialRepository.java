package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.Credential;
import java.util.Optional;
import java.util.UUID;

/** 凭据仓储：只存密码哈希，明文永不落库。 */
public interface CredentialRepository {

  Optional<String> findPasswordHash(UUID accountId);

  void insert(Credential credential);

  void updatePasswordHash(UUID accountId, String passwordHash);

  /**
   * 登录时的哈希升级：仅当库里的哈希仍是 {@code expectedHash} 才替换；期间密码已被改过则什么也不做（返回 {@code false}），避免旧密码的升级结果覆盖新密码。
   */
  boolean upgradePasswordHash(UUID accountId, String expectedHash, String passwordHash);
}
