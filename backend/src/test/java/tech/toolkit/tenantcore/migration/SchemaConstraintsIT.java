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

  private UUID insertUser(long tenant, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into app_user(id, tenant_id, display_name, role) values (?, ?, ?, 'MEMBER')",
        id,
        tenant,
        name);
    return id;
  }

  private void insertAccount(long tenant, UUID userId, String identifier) {
    jdbc.update(
        "insert into account(tenant_id, user_id, type, identifier, identifier_normalized)"
            + " values (?, ?, 'USERNAME_PASSWORD', ?, lower(?))",
        tenant,
        userId,
        identifier,
        identifier);
  }

  @Test
  void usernameIsUniquePerTenantCaseInsensitively() {
    String name = "u" + UUID.randomUUID().toString().substring(0, 8);
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s -> {
                  UUID user = insertUser(0, "A");
                  insertAccount(0, user, name);
                }));
    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    0L,
                    () ->
                        tx.executeWithoutResult(
                            s -> {
                              UUID user = insertUser(0, "B");
                              insertAccount(0, user, name.toUpperCase());
                            })))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void sameUsernameAllowedInAnotherTenant() {
    String name = "u" + UUID.randomUUID().toString().substring(0, 8);
    TenantContext.runAs(
        0L, () -> tx.executeWithoutResult(s -> insertAccount(0, insertUser(0, "A"), name)));
    TenantContext.runAs(
        1L, () -> tx.executeWithoutResult(s -> insertAccount(1, insertUser(1, "A"), name)));
  }

  @Test
  void accountMustReferenceUserOfTheSameTenant() {
    UUID[] tenant1User = new UUID[1];
    TenantContext.runAs(
        1L, () -> tx.executeWithoutResult(s -> tenant1User[0] = insertUser(1, "X")));
    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    0L,
                    () ->
                        tx.executeWithoutResult(s -> insertAccount(0, tenant1User[0], "cross-fk"))))
        .isInstanceOf(DataAccessException.class);
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
                                "insert into session(tenant_id, token_hash, user_id, account_id,"
                                    + " last_seen_at, absolute_expires_at)"
                                    + " values (0, ?, ?, ?, now(), now() + interval '8 hours')",
                                hash,
                                UUID.randomUUID(),
                                UUID.randomUUID())));
    insert.run();
    assertThatThrownBy(insert::run).isInstanceOf(DataAccessException.class);
  }

  @Test
  void auditLogIsAppendOnly() {
    UUID id = UUID.randomUUID();
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s ->
                    jdbc.update(
                        "insert into audit_log(id, tenant_id, action, result)"
                            + " values (?, 0, 'LOGIN_SUCCESS', 'SUCCESS')",
                        id)));
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
    TenantContext.runAs(0L, () -> tx.executeWithoutResult(s -> insertUser(0, "visible")));
    Integer count =
        tx.execute(s -> jdbc.queryForObject("select count(*) from app_user", Integer.class));
    assertThat(count).isZero();
  }
}
