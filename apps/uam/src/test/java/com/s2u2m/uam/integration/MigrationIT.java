package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.support.IntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** T013：Flyway 迁移可应用，且约束、索引与 data-model.md 一致。 */
class MigrationIT extends IntegrationTestBase {

  private UUID insertUser(String name, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_user (id, name, status, updated_at) VALUES (?, ?, ?, now())",
        id,
        name,
        status);
    return id;
  }

  private UUID insertAccount(UUID userId, String kind, String loginName, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO account (id, user_id, kind, login_name, login_name_norm, status)"
            + " VALUES (?, ?, ?, ?, lower(?), ?)",
        id,
        userId,
        kind,
        loginName,
        loginName,
        status);
    return id;
  }

  private List<String> indexDefs(String table) {
    return jdbc.queryForList(
        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?",
        String.class,
        table);
  }

  @Test
  void migrationsAreApplied() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '1'",
            Integer.class);
    assertThat(applied).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void userStatusOnlyAllowsActiveOrDisabled() {
    insertUser("甲", "ACTIVE");
    insertUser("乙", "DISABLED");
    assertThatThrownBy(() -> insertUser("丙", "DELETED"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void userNameIsNotUniqueButMustHaveOneToSixtyFourCharsAfterTrim() {
    insertUser("重名", "ACTIVE");
    insertUser("重名", "ACTIVE");
    assertThatThrownBy(() -> insertUser("   ", "ACTIVE"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertUser("x".repeat(65), "ACTIVE"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void userUpdatedAtHasNoDatabaseDefault() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO app_user (id, name, status) VALUES (?, 'x', 'ACTIVE')",
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void accountKindOnlyAllowsPassword() {
    UUID user = insertUser("甲", "ACTIVE");
    insertAccount(user, "PASSWORD", "alice", "ACTIVE");
    assertThatThrownBy(() -> insertAccount(user, "OIDC", "bob", "ACTIVE"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void accountStatusOnlyAllowsActiveDisabledDeleted() {
    UUID user = insertUser("甲", "ACTIVE");
    insertAccount(user, "PASSWORD", "a1", "ACTIVE");
    insertAccount(user, "PASSWORD", "a2", "DISABLED");
    insertAccount(user, "PASSWORD", "a3", "DELETED");
    assertThatThrownBy(() -> insertAccount(user, "PASSWORD", "a4", "LOCKED"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void loginNameNormIsGloballyUniqueIncludingDisabledUsers() {
    UUID active = insertUser("甲", "ACTIVE");
    UUID disabled = insertUser("乙", "DISABLED");
    insertAccount(disabled, "PASSWORD", "Alice", "DISABLED");
    // 大小写不敏感，且已停用用户的账号名仍被占用
    assertThatThrownBy(() -> insertAccount(active, "PASSWORD", "ALICE", "ACTIVE"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(indexDefs("account"))
        .anyMatch(d -> d.contains("UNIQUE INDEX") && d.contains("(login_name_norm)"));
  }

  @Test
  void loginNameNormMustEqualLowerOfLoginName() {
    UUID user = insertUser("甲", "ACTIVE");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO account (id, user_id, kind, login_name, login_name_norm, status)"
                        + " VALUES (?, ?, 'PASSWORD', 'Alice', 'bob', 'ACTIVE')",
                    UUID.randomUUID(),
                    user))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void accountUserIdIsForeignKeyAndIndexed() {
    assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), "PASSWORD", "ghost", "ACTIVE"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(foreignKeyTarget("account", "user_id")).isEqualTo("app_user");
    assertThat(indexDefs("account"))
        .anyMatch(d -> !d.contains("UNIQUE") && d.contains("(user_id)"));
  }

  @Test
  void credentialAccountIdIsPrimaryKeyAndForeignKey() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO credential (account_id, password_hash, updated_at)"
                        + " VALUES (?, 'x', now())",
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);

    UUID user = insertUser("甲", "ACTIVE");
    UUID account = insertAccount(user, "PASSWORD", "alice", "ACTIVE");
    jdbc.update(
        "INSERT INTO credential (account_id, password_hash, updated_at) VALUES (?, 'h', now())",
        account);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO credential (account_id, password_hash, updated_at)"
                        + " VALUES (?, 'h2', now())",
                    account))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(foreignKeyTarget("credential", "account_id")).isEqualTo("account");
    assertThat(indexDefs("credential"))
        .anyMatch(d -> d.contains("UNIQUE INDEX") && d.contains("(account_id)"));
  }

  @Test
  void credentialUpdatedAtHasNoDatabaseDefault() {
    UUID user = insertUser("甲", "ACTIVE");
    UUID account = insertAccount(user, "PASSWORD", "alice", "ACTIVE");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO credential (account_id, password_hash) VALUES (?, 'h')", account))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void trigramGinIndexesExistForSearch() {
    assertThat(indexDefs("app_user"))
        .anyMatch(d -> d.contains("USING gin") && d.contains("name gin_trgm_ops"));
    assertThat(indexDefs("account"))
        .anyMatch(d -> d.contains("USING gin") && d.contains("login_name_norm gin_trgm_ops"));
  }

  @Test
  void userStatusHasPlainIndex() {
    assertThat(indexDefs("app_user"))
        .anyMatch(d -> !d.contains("UNIQUE") && !d.contains("USING gin") && d.contains("(status)"));
  }

  @Test
  void createdAtDefaultsToNow() {
    UUID user = insertUser("甲", "ACTIVE");
    UUID account = insertAccount(user, "PASSWORD", "alice", "ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT created_at IS NOT NULL FROM app_user WHERE id = ?", Boolean.class, user))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT created_at IS NOT NULL FROM account WHERE id = ?", Boolean.class, account))
        .isTrue();
  }

  private String foreignKeyTarget(String table, String column) {
    return jdbc.queryForObject(
        "SELECT ccu.table_name FROM information_schema.table_constraints tc"
            + " JOIN information_schema.key_column_usage kcu"
            + "   ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema"
            + " JOIN information_schema.constraint_column_usage ccu"
            + "   ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema"
            + " WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public'"
            + "   AND tc.table_name = ? AND kcu.column_name = ?",
        String.class,
        table,
        column);
  }
}
