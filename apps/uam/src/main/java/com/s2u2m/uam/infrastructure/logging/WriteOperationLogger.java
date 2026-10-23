package com.s2u2m.uam.infrastructure.logging;

import com.s2u2m.uam.domain.model.OperatorContext;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.WriteOperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * 写操作日志（research R16）：每次写操作一条结构化日志，事件名 {@code uam.admin.write}，字段作为键值对输出（JSON 编码器把它们放成顶层字段）。 {@code
 * traceId} 不在这里写：它由 API 层的 {@code TraceContextFilter} 放进 MDC，JSON 编码器自动输出为顶层字段（再写一次会让编码器报重名）。
 * 所有取值都经 {@link LogRedaction}；被拒绝的操作也记录。
 */
@Component
public class WriteOperationLogger implements WriteOperationLog {

  /** 事件名。 */
  public static final String EVENT = "uam.admin.write";

  private static final Logger log = LoggerFactory.getLogger(WriteOperationLogger.class);

  private final Clock clock;

  public WriteOperationLogger(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void record(WriteOperationEvent event) {
    LoggingEventBuilder builder = event.outcome() == Outcome.FAILED ? log.atWarn() : log.atInfo();
    builder = field(builder, "ts", clock.now().toString());
    OperatorContext operator = event.operator();
    if (operator != null) {
      builder = field(builder, "operatorUserId", operator.userId());
      builder = field(builder, "operatorAccountId", operator.accountId());
    }
    builder = field(builder, "clientId", event.clientId());
    builder = field(builder, "action", event.action().value());
    builder = field(builder, "targetType", event.targetType());
    builder = field(builder, "targetId", event.targetId());
    builder = field(builder, "outcome", event.outcome().value());
    builder = field(builder, "reason", event.reason());
    builder.log(EVENT);
  }

  private static LoggingEventBuilder field(LoggingEventBuilder builder, String key, Object value) {
    String redacted = LogRedaction.valueFor(key, value == null ? null : value.toString());
    return redacted == null ? builder : builder.addKeyValue(key, redacted);
  }
}
