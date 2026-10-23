package com.s2u2m.uam.infrastructure.jdbc;

import com.s2u2m.uam.domain.model.Credential;
import com.s2u2m.uam.domain.port.CredentialRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 凭据仓储（显式 SQL）。{@code updated_at} 在插入与更新时显式取 {@code now()}；哈希从不进日志。 */
@Repository
public class JdbcCredentialRepository implements CredentialRepository {

  private final JdbcClient jdbc;

  public JdbcCredentialRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> findPasswordHash(UUID accountId) {
    return jdbc.sql("SELECT password_hash FROM credential WHERE account_id = :id")
        .param("id", accountId)
        .query(String.class)
        .optional();
  }

  @Override
  public void insert(Credential credential) {
    jdbc.sql(
            "INSERT INTO credential (account_id, password_hash, updated_at)"
                + " VALUES (:id, :hash, now())")
        .param("id", credential.accountId())
        .param("hash", credential.passwordHash())
        .update();
  }

  @Override
  public void updatePasswordHash(UUID accountId, String passwordHash) {
    jdbc.sql(
            "UPDATE credential SET password_hash = :hash, updated_at = clock_timestamp()"
                + " WHERE account_id = :id")
        .param("id", accountId)
        .param("hash", passwordHash)
        .update();
  }
}
