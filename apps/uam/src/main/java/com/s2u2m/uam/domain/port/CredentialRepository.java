package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.Credential;
import java.util.Optional;
import java.util.UUID;

/** 凭据仓储：只存密码哈希，明文永不落库。 */
public interface CredentialRepository {

  Optional<String> findPasswordHash(UUID accountId);

  void insert(Credential credential);

  void updatePasswordHash(UUID accountId, String passwordHash);
}
