package com.s2u2m.uam.infrastructure.jdbc;

import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.port.UserRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 用户仓储（显式 SQL）。{@code updated_at} 没有数据库默认值，插入时显式取 {@code now()}。 */
@Repository
public class JdbcUserRepository implements UserRepository {

  private final JdbcClient jdbc;

  public JdbcUserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<User> findById(UUID id) {
    return jdbc.sql("SELECT id, name, status, created_at FROM app_user WHERE id = :id")
        .param("id", id)
        .query(JdbcUserRepository::map)
        .optional();
  }

  @Override
  public void insert(User user) {
    jdbc.sql(
            "INSERT INTO app_user (id, name, status, created_at, updated_at)"
                + " VALUES (:id, :name, :status, :createdAt, now())")
        .param("id", user.id())
        .param("name", user.name())
        .param("status", user.status().name())
        .param("createdAt", OffsetDateTime.ofInstant(user.createdAt(), ZoneOffset.UTC))
        .update();
  }

  @Override
  public long count() {
    return jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
  }

  private static User map(ResultSet rs, int row) throws SQLException {
    return new User(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        UserStatus.valueOf(rs.getString("status")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
