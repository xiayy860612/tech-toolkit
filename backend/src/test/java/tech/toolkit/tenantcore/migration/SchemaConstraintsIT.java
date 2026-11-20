package tech.toolkit.tenantcore.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;

class SchemaConstraintsIT extends AbstractPostgresIT {

  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  private Long insertUser(long tenant, String name, String username) {
    return jdbc.queryForObject(
        "insert into app_user(tenant_id, display_name, role, username, username_normalized,"
            + " password_hash, must_change_password)"
            + " values (?, ?, 'MEMBER', ?, lower(?), 'hash', false) returning id",
        Long.class,
        tenant,
        name,
        username,
        username);
  }

  private static String unique() {
    return "u" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void usernameIsUniquePerTenantCaseInsensitively() {
    String name = unique();
    TenantContext.runAs(0L, () -> tx.executeWithoutResult(s -> insertUser(0, "A", name)));
    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    0L, () -> tx.executeWithoutResult(s -> insertUser(0, "B", name.toUpperCase()))))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void sameUsernameAllowedInAnotherTenant() {
    String name = unique();
    TenantContext.runAs(0L, () -> tx.executeWithoutResult(s -> insertUser(0, "A", name)));
    TenantContext.runAs(1L, () -> tx.executeWithoutResult(s -> insertUser(1, "A", name)));
  }

  @Test
  void everyUserMustHaveAUsernameAndAPasswordHash() {
    for (String column : new String[] {"username", "username_normalized", "password_hash"}) {
      assertThat(
              new JdbcTemplate(ownerDataSource())
                  .queryForObject(
                      "select is_nullable from information_schema.columns"
                          + " where table_name = 'app_user' and column_name = ?",
                      String.class,
                      column))
          .as(column)
          .isEqualTo("NO");
    }
  }

  @Test
  void aUserHasNoPhoneOrEmailColumns() {
    JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
    assertThat(
            owner.queryForObject(
                "select count(*) from information_schema.columns"
                    + " where table_name = 'app_user' and column_name in ('phone', 'email')",
                Integer.class))
        .isZero();
  }

  @Test
  void userAndAuditIdsAreIncreasingIntegersButSessionIdsAreUuids() {
    JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
    assertThat(columnType(owner, "app_user", "id")).isEqualTo("bigint");
    assertThat(columnType(owner, "audit_log", "id")).isEqualTo("bigint");
    assertThat(columnType(owner, "session", "id")).isEqualTo("uuid");
    Long[] ids = new Long[2];
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s -> {
                  ids[0] = insertUser(0, "A", unique());
                  ids[1] = insertUser(0, "B", unique());
                }));
    assertThat(ids[1]).isGreaterThan(ids[0]);
  }

  @Test
  void thereIsNoAccountTableYetAndNoSeparatePasswordCredentialTable() {
    JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
    for (String table : new String[] {"account", "password_credential"}) {
      assertThat(
              owner.queryForObject(
                  "select count(*) from information_schema.tables where table_name = ?",
                  Integer.class,
                  table))
          .as(table)
          .isZero();
    }
  }

  private static String columnType(JdbcTemplate owner, String table, String column) {
    return owner.queryForObject(
        "select data_type from information_schema.columns where table_name = ? and column_name = ?",
        String.class,
        table,
        column);
  }

  @Test
  void sessionTokenHashIsUnique() {
    byte[] hash = UUID.randomUUID().toString().getBytes();
    Runnable insert =
        () ->
            TenantContext.runAs(
                0L,
                () ->
                    tx.executeWithoutResult(
                        s ->
                            jdbc.update(
                                "insert into session(tenant_id, token_hash, user_id,"
                                    + " last_seen_at, absolute_expires_at)"
                                    + " values (0, ?, 1, now(), now() + interval '8 hours')",
                                hash)));
    insert.run();
    assertThatThrownBy(insert::run).isInstanceOf(DataAccessException.class);
  }

  @Test
  void auditLogIsAppendOnly() {
    Long[] holder = new Long[1];
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s ->
                    holder[0] =
                        jdbc.queryForObject(
                            "insert into audit_log(tenant_id, action, result)"
                                + " values (0, 'LOGIN_SUCCESS', 'SUCCESS') returning id",
                            Long.class)));
    Long id = holder[0];
    // 应用角色无 UPDATE/DELETE 权限
    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    0L,
                    () ->
                        tx.executeWithoutResult(
                            s ->
                                jdbc.update(
                                    "update audit_log set result='FAILURE' where id=?", id))))
        .isInstanceOf(DataAccessException.class);
    // 即使是表属主，触发器也拒绝更新与删除
    JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
    assertThatThrownBy(() -> owner.update("update audit_log set result='FAILURE' where id=?", id))
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> owner.update("delete from audit_log where id=?", id))
        .hasMessageContaining("append-only");
  }

  @Test
  void rowLevelSecurityReturnsNothingWithoutTenantContext() {
    TenantContext.runAs(0L, () -> tx.executeWithoutResult(s -> insertUser(0, "visible", unique())));
    Integer count =
        tx.execute(s -> jdbc.queryForObject("select count(*) from app_user", Integer.class));
    assertThat(count).isZero();
  }
}
