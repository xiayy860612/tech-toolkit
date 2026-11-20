package tech.toolkit.tenantcore.audit;

/**
 * 审计写入端口。首期实现写本库 {@code audit_log}（带租户隔离）；日后 Audit 基础服务落地时可替换为发布到 Event Bus，业务代码只依赖 {@link
 * AuditService}/{@link AuditSink}。
 */
public interface AuditSink {
  void write(AuditEvent event);
}
