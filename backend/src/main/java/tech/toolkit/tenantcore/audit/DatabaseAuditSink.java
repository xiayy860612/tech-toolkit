package tech.toolkit.tenantcore.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tech.toolkit.tenantcore.platform.IpAddresses;

/** 首期审计实现：写本库 audit_log（带 tenant_id，受 RLS 约束）。 */
@Component
class DatabaseAuditSink implements AuditSink {

  private static final Pattern SENSITIVE =
      Pattern.compile("(?i).*(password|passwd|token|secret|jwt|authorization|credential).*");

  private final AuditLogRepository repository;

  DatabaseAuditSink(AuditLogRepository repository) {
    this.repository = repository;
  }

  @Override
  public void write(AuditEvent event) {
    repository.save(new AuditLog(event, IpAddresses.parse(event.ip()), sanitize(event.detail())));
  }

  /** 递归移除敏感键，保证审计详情永不含密码、令牌或 JWT。 */
  static Map<String, Object> sanitize(Map<String, Object> detail) {
    if (detail == null) {
      return null;
    }
    Map<String, Object> clean = new LinkedHashMap<>();
    detail.forEach(
        (k, v) -> {
          if (SENSITIVE.matcher(k).matches()) {
            clean.put(k, "[REDACTED]");
          } else if (v instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) nested;
            clean.put(k, sanitize(cast));
          } else {
            clean.put(k, v);
          }
        });
    return clean;
  }
}
