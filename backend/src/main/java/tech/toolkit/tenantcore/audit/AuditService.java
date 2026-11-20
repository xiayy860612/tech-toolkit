package tech.toolkit.tenantcore.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

  private final AuditSink sink;

  public AuditService(AuditSink sink) {
    this.sink = sink;
  }

  /** 与业务在同一事务内提交：要么都成功，要么都回滚。 */
  @Transactional
  public void record(AuditEvent event) {
    sink.write(event);
  }

  /** 独立事务：用于失败登录等"业务回滚也要留痕"的场景。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordIndependently(AuditEvent event) {
    sink.write(event);
  }
}
