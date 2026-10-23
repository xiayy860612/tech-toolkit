package com.s2u2m.uam.infrastructure.jdbc;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.port.AccountRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 账号仓储（显式 SQL）。{@code login_name_norm = lower(login_name)}，全局唯一索引兜底账号名唯一性。 */
@Repository
public class JdbcAccountRepository implements AccountRepository {

  private final JdbcClient jdbc;

  public JdbcAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Account> findByLoginName(LoginName name) {
    return jdbc.sql(
            "SELECT id, user_id, kind, login_name, status, created_at FROM account"
                + " WHERE login_name_norm = :norm")
        .param("norm", name.normalized())
        .query(JdbcAccountRepository::map)
        .optional();
  }

  @Override
  public boolean existsByLoginName(LoginName name) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM account WHERE login_name_norm = :norm)")
        .param("norm", name.normalized())
        .query(Boolean.class)
        .single();
  }

  @Override
  public void insert(Account account) {
    try {
      jdbc.sql(
              "INSERT INTO account (id, user_id, kind, login_name, login_name_norm, status, created_at)"
                  + " VALUES (:id, :userId, :kind, :loginName, :norm, :status, :createdAt)")
          .param("id", account.id())
          .param("userId", account.userId())
          .param("kind", account.kind().name())
          .param("loginName", account.loginName().raw())
          .param("norm", account.loginName().normalized())
          .param("status", account.status().name())
          .param("createdAt", OffsetDateTime.ofInstant(account.createdAt(), ZoneOffset.UTC))
          .update();
    } catch (DuplicateKeyException e) {
      throw new DomainException(ErrorCode.ACCOUNT_NAME_TAKEN, "账号名已被使用");
    }
  }

  private static Account map(ResultSet rs, int row) throws SQLException {
    return new Account(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        AccountKind.valueOf(rs.getString("kind")),
        LoginName.of(rs.getString("login_name")),
        AccountStatus.valueOf(rs.getString("status")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
