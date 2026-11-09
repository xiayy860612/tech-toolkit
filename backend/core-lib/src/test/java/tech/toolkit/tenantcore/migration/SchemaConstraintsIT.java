package tech.toolkit.tenantcore.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tech.toolkit.tenantcore.support.PostgresTestBase;

class SchemaConstraintsIT extends PostgresTestBase {

  private final JdbcClient owner = JdbcClient.create(ownerDataSource());

  private UUID tenant(String slug) {
    UUID id = UUID.randomUUID();
    owner
        .sql("INSERT INTO core_tenant(id, slug, name, created_at) VALUES (?, ?, 'Acme', now())")
        .params(id, slug)
        .update();
    return id;
  }

  private UUID user(UUID tenantId, String displayName) {
    UUID id = UUID.randomUUID();
    owner
        .sql(
            "INSERT INTO uam_user(id, tenant_user_id, tenant_id, display_name, created_at)"
                + " VALUES (?, ?, ?, ?, now())")
        .params(id, "tu_" + id.toString().substring(0, 12), tenantId, displayName)
        .update();
    return id;
  }

  private void account(UUID userId, UUID tenantId, String subject) {
    owner
        .sql(
            "INSERT INTO uam_account(id, user_id, tenant_id, type, provider, subject, created_at)"
                + " VALUES (?, ?, ?, 'PASSWORD', '', ?, now())")
        .params(UUID.randomUUID(), userId, tenantId, subject)
        .update();
  }

  @Test
  void tenantSlugMustMatchPatternAndBeUnique() {
    tenant("acme");
    assertThatThrownBy(() -> tenant("acme")).isInstanceOf(DataIntegrityViolationException.class);
    for (String bad : List.of("Ab", "ab", "1abc", "acme_corp", "a".repeat(33), "ACME")) {
      assertThatThrownBy(() -> tenant(bad))
          .as("slug %s", bad)
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Test
  void tenantStatusAndNameAreChecked() {
    UUID id = tenant("acme");
    assertThatThrownBy(
            () ->
                owner.sql("UPDATE core_tenant SET status = 'GONE' WHERE id = ?").param(id).update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                owner
                    .sql(
                        "INSERT INTO core_tenant(id, slug, name, created_at)"
                            + " VALUES (gen_random_uuid(), 'other', '', now())")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void moduleSubscriptionIsLimitedToPlatformAndUniquePerTenant() {
    UUID tenantId = tenant("acme");
    String sql =
        "INSERT INTO core_module_subscription(id, tenant_id, module_code, subscribed_at)"
            + " VALUES (gen_random_uuid(), ?, ?, now())";
    owner.sql(sql).params(tenantId, "platform").update();
    assertThatThrownBy(() -> owner.sql(sql).params(tenantId, "platform").update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> owner.sql(sql).params(tenantId, "billing").update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void userDisplayNameLengthAndStatusAreChecked() {
    UUID tenantId = tenant("acme");
    assertThatThrownBy(() -> user(tenantId, ""))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> user(tenantId, "x".repeat(101)))
        .isInstanceOf(DataIntegrityViolationException.class);
    UUID userId = user(tenantId, "Zhang San");
    assertThatThrownBy(
            () ->
                owner
                    .sql("UPDATE uam_user SET status = 'GONE' WHERE id = ?")
                    .param(userId)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void accountSubjectMustMatchPatternBeLowercaseAndUniquePerTenant() {
    UUID tenantId = tenant("acme");
    UUID first = user(tenantId, "First");
    UUID second = user(tenantId, "Second");
    account(first, tenantId, "admin");
    assertThatThrownBy(() -> account(second, tenantId, "admin"))
        .isInstanceOf(DataIntegrityViolationException.class);
    for (String bad : List.of("Admin", "ab", "-admin", "ad min", "x".repeat(65))) {
      assertThatThrownBy(() -> account(second, tenantId, bad))
          .as("subject %s", bad)
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Test
  void aUserHasAtMostOnePasswordAccount() {
    UUID tenantId = tenant("acme");
    UUID userId = user(tenantId, "First");
    account(userId, tenantId, "admin");
    assertThatThrownBy(() -> account(userId, tenantId, "admin2"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void opaqueTokenSidIsUnique() {
    UUID tenantId = tenant("acme");
    UUID userId = user(tenantId, "First");
    account(userId, tenantId, "admin");
    UUID accountId = owner.sql("SELECT id FROM uam_account").query(UUID.class).single();
    String sql =
        "INSERT INTO uam_opaque_token(token_hash, sid, account_id, user_id, tenant_id,"
            + " created_at, last_seen_at, expires_at) VALUES (?, 's_dup', ?, ?, ?, now(), now(),"
            + " now())";
    owner.sql(sql).params(new byte[] {1}, accountId, userId, tenantId).update();
    assertThatThrownBy(
            () -> owner.sql(sql).params(new byte[] {2}, accountId, userId, tenantId).update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void noForeignKeyCrossesPackageBoundaries() {
    List<String[]> pairs =
        owner
            .sql(
                "SELECT conrelid::regclass::text, confrelid::regclass::text"
                    + " FROM pg_constraint WHERE contype = 'f'")
            .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)})
            .list();
    assertThat(pairs).isNotEmpty();
    for (String[] pair : pairs) {
      assertThat(prefix(pair[0])).as("%s -> %s", pair[0], pair[1]).isEqualTo(prefix(pair[1]));
    }
  }

  private static String prefix(String table) {
    return table.substring(0, table.indexOf('_'));
  }

  @Test
  void applicationRoleCannotModifyAuditEvents() {
    JdbcClient app = JdbcClient.create(appDataSource());
    app.sql(
            "INSERT INTO audit_event(id, occurred_at, actor, action, target, outcome)"
                + " VALUES (gen_random_uuid(), now(), 'cli:test@host', 'tenant.create', 'acme',"
                + " 'SUCCESS')")
        .update();
    assertThat(app.sql("SELECT count(*) FROM audit_event").query(Long.class).single())
        .isEqualTo(1L);
    assertThatThrownBy(() -> app.sql("UPDATE audit_event SET actor = 'x'").update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> app.sql("DELETE FROM audit_event").update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> app.sql("TRUNCATE audit_event").update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void applicationRoleCanUseBusinessTables() {
    JdbcClient app = JdbcClient.create(appDataSource());
    app.sql("INSERT INTO core_tenant(id, slug, name, created_at) VALUES (?, 'zzz', 'Z', now())")
        .param(UUID.randomUUID())
        .update();
    assertThat(app.sql("SELECT count(*) FROM core_tenant").query(Long.class).single())
        .isEqualTo(1L);
  }
}
