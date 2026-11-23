package tech.toolkit.auth.audit;

import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Appends security events to {@code auth_audit_event} and mirrors them to the log. */
@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  /** Detail keys that name secrets are refused outright. */
  private static final Pattern SECRET_KEY =
      Pattern.compile(".*(password|secret|token|authorization).*", Pattern.CASE_INSENSITIVE);

  /** A compact JWT (three base64url parts starting with a JSON header). */
  private static final Pattern JWT_LIKE =
      Pattern.compile("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*$");

  private final JdbcTemplate jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  public AuditService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(AuditEvent event) {
    validate(event.detail());
    jdbc.update(
        "insert into auth_audit_event (type, actor, subject_user_id, client_id, source_ip, result,"
            + " detail) values (?, ?, ?, ?, ?, ?, ?::jsonb)",
        event.type().name(),
        event.actor(),
        event.subjectUserId(),
        event.clientId(),
        event.sourceIp(),
        event.success() ? "SUCCESS" : "FAILURE",
        json.writeValueAsString(event.detail()));
    log.info(
        "audit type={} result={} actor={} subject={} client={} ip={} detailKeys={}",
        event.type(),
        event.success() ? "SUCCESS" : "FAILURE",
        clean(event.actor()),
        event.subjectUserId(),
        clean(event.clientId()),
        clean(event.sourceIp()),
        event.detail().keySet());
  }

  private static void validate(Map<String, Object> detail) {
    detail.forEach(
        (key, value) -> {
          if (SECRET_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("audit detail key not allowed: " + key);
          }
          if (value instanceof CharSequence text && JWT_LIKE.matcher(text).matches()) {
            throw new IllegalArgumentException("audit detail value looks like a token: " + key);
          }
        });
  }

  /** Keeps caller-controlled values on one log line. */
  private static String clean(String value) {
    return value == null ? null : value.replaceAll("[\\r\\n]", "_");
  }
}
