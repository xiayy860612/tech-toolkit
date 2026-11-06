package tech.toolkit.tenantcore.audit;

import java.sql.Types;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Appends audit events. Refuses targets that look like passwords hashes or tokens. */
public class AuditRecorder {

  private static final Pattern SECRET_LIKE =
      Pattern.compile(
          "tk_[A-Za-z0-9_-]{20,}|\\$argon2|eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");

  private final JdbcClient jdbc;
  private final Clock clock;

  public AuditRecorder(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public void success(String actor, String action, UUID tenantId, String target) {
    record(new AuditEvent(actor, action, tenantId, target, AuditEvent.Outcome.SUCCESS, null));
  }

  public void noop(String actor, String action, UUID tenantId, String target) {
    record(new AuditEvent(actor, action, tenantId, target, AuditEvent.Outcome.NOOP, null));
  }

  public void failure(String actor, String action, UUID tenantId, String target) {
    record(new AuditEvent(actor, action, tenantId, target, AuditEvent.Outcome.FAILURE, null));
  }

  public void record(AuditEvent event) {
    if (event.target() != null && SECRET_LIKE.matcher(event.target()).find()) {
      throw new IllegalArgumentException("Audit target must not contain secrets");
    }
    jdbc.sql(
            "INSERT INTO audit_event(id, occurred_at, actor, action, tenant_id, target, outcome,"
                + " trace_id) VALUES (:id, :at, :actor, :action, :tenant, :target, :outcome,"
                + " :trace)")
        .param("id", UUID.randomUUID())
        .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        .param("actor", event.actor())
        .param("action", event.action())
        .param("tenant", event.tenantId(), Types.OTHER)
        .param("target", event.target() == null ? "" : event.target())
        .param("outcome", event.outcome().name())
        .param("trace", event.traceId(), Types.VARCHAR)
        .update();
  }
}
