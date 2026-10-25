package com.s2u2m.uam.infrastructure.logging;

import com.s2u2m.uam.domain.model.ClientRejection;
import com.s2u2m.uam.domain.port.ClientRejectionLog;
import com.s2u2m.uam.domain.port.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 拒绝日志（research R4）：事件名 {@code uam.client.rejected}，字段为来源地址、时间、原因类别与调用目标，作为键值对输出（JSON 编码器放成顶层字段），
 * 并经 {@link LogRedaction}。
 *
 * <p>日志量有上限：每秒至多 {@value #MAX_EVENTS_PER_SECOND} 条，超出的只计数，下一秒的第一条带上 {@code
 * suppressedBefore}。这样被拒绝的洪水 （例如猜密钥）不会刷爆日志，也不占用与攻击量成正比的内存（只有几个计数器）。
 */
@Component
@Profile("!cli")
public class ClientRejectionLogger implements ClientRejectionLog {

  /** 事件名。 */
  public static final String EVENT = "uam.client.rejected";

  /** 每秒最多写的条数。 */
  public static final int MAX_EVENTS_PER_SECOND = 200;

  private static final int MAX_PATH_LENGTH = 200;

  private static final Logger log = LoggerFactory.getLogger(ClientRejectionLogger.class);

  private final Clock clock;
  private long window = Long.MIN_VALUE;
  private int written;
  private long suppressed;

  public ClientRejectionLogger(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void record(ClientRejection rejection) {
    var now = clock.now();
    long suppressedBefore = admit(now.getEpochSecond());
    if (suppressedBefore < 0) {
      return;
    }
    LoggingEventBuilder builder = log.atWarn();
    builder = field(builder, "ts", now.toString());
    builder = field(builder, "sourceAddress", rejection.sourceAddress());
    builder = field(builder, "reason", rejection.reason().value());
    builder = builder.addKeyValue("status", rejection.status());
    builder = field(builder, "method", rejection.method());
    builder = field(builder, "path", truncate(rejection.path()));
    builder = field(builder, "clientId", rejection.clientId());
    if (suppressedBefore > 0) {
      builder = builder.addKeyValue("suppressedBefore", suppressedBefore);
    }
    builder.log(EVENT);
  }

  /** 是否放行本条：返回 -1 表示丢弃；否则返回「上一个窗口被丢弃的条数」（0 表示没有）。 */
  private synchronized long admit(long second) {
    long carried = 0;
    if (second != window) {
      window = second;
      written = 0;
      carried = suppressed;
      suppressed = 0;
    }
    if (written >= MAX_EVENTS_PER_SECOND) {
      suppressed++;
      return -1;
    }
    written++;
    return carried;
  }

  private static String truncate(String path) {
    return path == null || path.length() <= MAX_PATH_LENGTH
        ? path
        : path.substring(0, MAX_PATH_LENGTH);
  }

  private static LoggingEventBuilder field(LoggingEventBuilder builder, String key, String value) {
    String redacted = LogRedaction.valueFor(key, value);
    return redacted == null ? builder : builder.addKeyValue(key, redacted);
  }
}
