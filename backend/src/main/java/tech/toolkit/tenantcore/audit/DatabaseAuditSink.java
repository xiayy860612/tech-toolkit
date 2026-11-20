package tech.toolkit.tenantcore.audit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 首期审计实现：写本库 audit_log（带 tenant_id，受 RLS 约束）。 */
@Component
class DatabaseAuditSink implements AuditSink {

  private static final Pattern SENSITIVE =
      Pattern.compile("(?i).*(password|passwd|token|secret|jwt|authorization|credential).*");
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  private final AuditLogRepository repository;

  DatabaseAuditSink(AuditLogRepository repository) {
    this.repository = repository;
  }

  @Override
  public void write(AuditEvent event) {
    repository.save(new AuditLog(event, parseIp(event.ip()), sanitize(event.detail())));
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

  /** 只解析 IP 字面量，避免触发 DNS 查询。 */
  static InetAddress parseIp(String ip) {
    if (ip == null || ip.isBlank()) {
      return null;
    }
    if (!IPV4.matcher(ip).matches() && !ip.contains(":")) {
      return null;
    }
    try {
      return InetAddress.getByName(ip);
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
