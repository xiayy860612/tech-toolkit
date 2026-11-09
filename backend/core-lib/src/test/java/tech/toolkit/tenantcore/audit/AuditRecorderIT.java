package tech.toolkit.tenantcore.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tech.toolkit.tenantcore.audit.AuditEvent.Outcome;
import tech.toolkit.tenantcore.support.PostgresTestBase;

class AuditRecorderIT extends PostgresTestBase {

  private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T08:00:00Z"), ZoneOffset.UTC);
  private final JdbcClient jdbc = JdbcClient.create(appDataSource());
  private final AuditRecorder recorder = new AuditRecorder(jdbc, clock);

  @Test
  void recordsSuccessFailureAndNoopEvents() {
    UUID tenantId = UUID.randomUUID();
    recorder.record(
        new AuditEvent("cli:amos@host", "tenant.create", tenantId, "acme", Outcome.SUCCESS, null));
    recorder.record(
        new AuditEvent(
            "anonymous", "login.failure", null, "tenant=acme", Outcome.FAILURE, "trace-1"));
    recorder.record(
        new AuditEvent("cli:amos@host", "tenant.create", tenantId, "acme", Outcome.NOOP, null));

    List<Map<String, Object>> rows =
        jdbc.sql(
                "SELECT actor, action, tenant_id, target, outcome, trace_id, occurred_at"
                    + " FROM audit_event ORDER BY outcome")
            .query()
            .listOfRows();
    assertThat(rows).hasSize(3);
    assertThat(rows)
        .extracting(r -> r.get("outcome"))
        .containsExactly("FAILURE", "NOOP", "SUCCESS");
    Map<String, Object> failure = rows.get(0);
    assertThat(failure.get("actor")).isEqualTo("anonymous");
    assertThat(failure.get("tenant_id")).isNull();
    assertThat(failure.get("trace_id")).isEqualTo("trace-1");
    assertThat(rows.get(2).get("tenant_id")).isEqualTo(tenantId);
  }

  @Test
  void rejectsTargetsThatLookLikeSecrets() {
    for (String secret :
        List.of(
            "tk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
            "$argon2id$v=19$m=19456,t=2,p=1$abc$def",
            "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ0dV8xIn0.signature")) {
      assertThatThrownBy(
              () ->
                  recorder.record(
                      new AuditEvent(
                          "anonymous", "login.failure", null, secret, Outcome.FAILURE, null)))
          .as(secret)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single()).isZero();
  }
}
