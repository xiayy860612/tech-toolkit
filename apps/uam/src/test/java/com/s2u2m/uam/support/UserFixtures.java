package com.s2u2m.uam.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 集成测试造数：直接用 SQL 写入用户、账号与凭据（不经过较慢的 Argon2id），可指定状态与创建时间。 */
public final class UserFixtures {

  private final JdbcTemplate jdbc;

  public UserFixtures(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UUID user(String name) {
    return user(name, "ACTIVE", Instant.now());
  }

  public UUID user(String name, String status, Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_user (id, name, status, created_at, updated_at) VALUES (?, ?, ?, ?, now())",
        id,
        name,
        status,
        utc(createdAt));
    return id;
  }

  public UUID account(UUID userId, String loginName) {
    return account(userId, loginName, "ACTIVE", Instant.now());
  }

  public UUID account(UUID userId, String loginName, String status, Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO account (id, user_id, kind, login_name, login_name_norm, status, created_at)"
            + " VALUES (?, ?, 'PASSWORD', ?, lower(?), ?, ?)",
        id,
        userId,
        loginName,
        loginName,
        status,
        utc(createdAt));
    return id;
  }

  /** 写入一条凭据（哈希是明显的假值），用来断言接口不会把它带出来。 */
  public void credential(UUID accountId, String passwordHash) {
    jdbc.update(
        "INSERT INTO credential (account_id, password_hash, updated_at) VALUES (?, ?, now())",
        accountId,
        passwordHash);
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
