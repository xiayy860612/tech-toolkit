package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.s2u2m.uam.domain.model.ClientRejection;
import com.s2u2m.uam.domain.model.ClientRejection.Reason;
import com.s2u2m.uam.infrastructure.logging.ClientRejectionLogger;
import com.s2u2m.uam.support.Fakes.FakeClock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** T180：被拒绝的调用写结构化日志（事件名、来源、时间、原因类别），经脱敏层；日志量有上限，防止被拒绝的洪水刷爆日志。 */
class ClientRejectionLoggerTest {

  private static final Instant NOW = Instant.parse("2026-10-24T08:00:00.500Z");

  private final Logger logger = (Logger) LoggerFactory.getLogger(ClientRejectionLogger.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final FakeClock clock = new FakeClock(NOW);
  private final ClientRejectionLogger rejectionLog = new ClientRejectionLogger(clock);

  @BeforeEach
  void attach() {
    logger.setLevel(Level.INFO);
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
    appender.stop();
  }

  private static Map<String, Object> fields(ILoggingEvent event) {
    Map<String, Object> map = new LinkedHashMap<>();
    if (event.getKeyValuePairs() != null) {
      event.getKeyValuePairs().forEach(kv -> map.put(kv.key, kv.value));
    }
    return map;
  }

  private static ClientRejection rejection(Reason reason) {
    return new ClientRejection(reason, "10.0.0.7", "POST", "/v1/auth/login", null, 401);
  }

  @Test
  void writesOneWarnEventWithSourceTimeAndReasonCategory() {
    rejectionLog.record(rejection(Reason.UNKNOWN_KEY));

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage()).isEqualTo("uam.client.rejected");
    assertThat(fields(event))
        .containsEntry("ts", "2026-10-24T08:00:00.500Z")
        .containsEntry("sourceAddress", "10.0.0.7")
        .containsEntry("reason", "unknown_key")
        .containsEntry("status", 401)
        .containsEntry("method", "POST")
        .containsEntry("path", "/v1/auth/login")
        .doesNotContainKey("clientId");
  }

  @Test
  void forbiddenCallsNameTheIdentifiedClient() {
    rejectionLog.record(
        new ClientRejection(
            Reason.FORBIDDEN, "10.0.0.7", "DELETE", "/v1/sessions/x", "reporting", 403));

    Map<String, Object> fields = fields(appender.list.get(0));
    assertThat(fields).containsEntry("reason", "forbidden").containsEntry("clientId", "reporting");
    assertThat(fields).containsEntry("status", 403);
  }

  @Test
  void everyReasonHasAStableLowerSnakeCaseCategory() {
    assertThat(Reason.values())
        .extracting(Reason::value)
        .containsExactlyInAnyOrder("missing_key", "malformed", "unknown_key", "forbidden");
  }

  @Test
  void secretsThatSomehowReachTheFieldsAreRedactedAndLongPathsTruncated() {
    String leaked = "uamk_" + "A".repeat(43);
    rejectionLog.record(
        new ClientRejection(
            Reason.UNKNOWN_KEY,
            "10.0.0.7",
            "GET",
            "/v1/" + leaked + "/" + "x".repeat(500),
            null,
            401));

    String path = (String) fields(appender.list.get(0)).get("path");
    assertThat(path).doesNotContain(leaked).hasSizeLessThanOrEqualTo(200);
  }

  @Test
  void floodsAreCappedPerSecondAndTheNextSecondReportsHowManyWereSuppressed() {
    for (int i = 0; i < ClientRejectionLogger.MAX_EVENTS_PER_SECOND + 30; i++) {
      rejectionLog.record(rejection(Reason.MISSING_KEY));
    }
    assertThat(appender.list).hasSize(ClientRejectionLogger.MAX_EVENTS_PER_SECOND);

    clock.advance(Duration.ofSeconds(1));
    rejectionLog.record(rejection(Reason.MISSING_KEY));

    assertThat(appender.list).hasSize(ClientRejectionLogger.MAX_EVENTS_PER_SECOND + 1);
    Map<String, Object> next = fields(appender.list.get(appender.list.size() - 1));
    assertThat(next).containsEntry("suppressedBefore", 30L);

    clock.advance(Duration.ofSeconds(1));
    rejectionLog.record(rejection(Reason.MISSING_KEY));
    assertThat(fields(appender.list.get(appender.list.size() - 1)))
        .doesNotContainKey("suppressedBefore");
  }
}
