package tech.toolkit.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.toolkit.auth.support.PostgresTestBase;

class AuditServiceIT extends PostgresTestBase {

  @Autowired AuditService audit;
  @Autowired JdbcTemplate appJdbc;

  @Test
  void recordsAnEventWithAllFields() {
    audit.record(
        AuditEvent.of(AuditType.LOGIN_FAILURE, false)
            .actor("42")
            .subjectUserId(7L)
            .clientId("client1")
            .sourceIp("10.0.0.1")
            .detail(Map.of("reason", "bad_credentials")));

    Map<String, Object> row = ownerJdbc().queryForMap("select * from auth_audit_event");
    assertThat(row)
        .containsEntry("type", "LOGIN_FAILURE")
        .containsEntry("actor", "42")
        .containsEntry("subject_user_id", 7L)
        .containsEntry("client_id", "client1")
        .containsEntry("source_ip", "10.0.0.1")
        .containsEntry("result", "FAILURE");
    assertThat(row.get("occurred_at")).isNotNull();
    assertThat(row.get("detail").toString()).contains("bad_credentials");
  }

  @Test
  void recordsSuccessWithoutOptionalFields() {
    audit.record(AuditEvent.of(AuditType.LOGOUT, true));

    Map<String, Object> row = ownerJdbc().queryForMap("select * from auth_audit_event");
    assertThat(row).containsEntry("type", "LOGOUT").containsEntry("result", "SUCCESS");
    assertThat(row.get("actor")).isNull();
    assertThat(row.get("subject_user_id")).isNull();
  }

  @Test
  void appRoleCannotRewriteOrRemoveEvents() {
    audit.record(AuditEvent.of(AuditType.LOGIN_SUCCESS, true));

    assertThatThrownBy(() -> appJdbc.update("update auth_audit_event set result = 'FAILURE'"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> appJdbc.update("delete from auth_audit_event"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void rejectsDetailKeysThatLookLikeSecrets() {
    for (String key : new String[] {"password", "client_secret", "access_token", "Refresh_Token"}) {
      assertThatThrownBy(
              () ->
                  audit.record(
                      AuditEvent.of(AuditType.LOGIN_FAILURE, false).detail(Map.of(key, "x"))))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(ownerJdbc().queryForObject("select count(*) from auth_audit_event", Integer.class))
        .isZero();
  }

  @Test
  void rejectsDetailValuesThatLookLikeTokens() {
    String jwt = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl";
    assertThatThrownBy(
            () ->
                audit.record(
                    AuditEvent.of(AuditType.TOKEN_ISSUED, true).detail(Map.of("note", jwt))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ownerJdbc().queryForObject("select count(*) from auth_audit_event", Integer.class))
        .isZero();
  }
}
