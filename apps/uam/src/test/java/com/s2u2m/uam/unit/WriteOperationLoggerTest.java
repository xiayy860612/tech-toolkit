package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.s2u2m.uam.domain.model.OperatorContext;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.infrastructure.logging.WriteOperationLogger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** T020：写操作日志的事件名、字段与「被拒绝的操作也记录」。 */
class WriteOperationLoggerTest {

  private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");

  private final Logger logger = (Logger) LoggerFactory.getLogger(WriteOperationLogger.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final WriteOperationLogger writeLog = new WriteOperationLogger(() -> NOW);

  private final UUID operatorUser = UUID.randomUUID();
  private final UUID operatorAccount = UUID.randomUUID();
  private final UUID sessionRef = UUID.randomUUID();
  private final OperatorContext operator =
      new OperatorContext(operatorUser, operatorAccount, sessionRef);

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
    MDC.clear();
  }

  private Map<String, Object> fields(ILoggingEvent event) {
    Map<String, Object> map = new LinkedHashMap<>();
    if (event.getKeyValuePairs() != null) {
      event.getKeyValuePairs().forEach(kv -> map.put(kv.key, kv.value));
    }
    return map;
  }

  @Test
  void successfulWriteIsLoggedWithAllFields() {
    MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
    UUID target = UUID.randomUUID();

    writeLog.record(
        new WriteOperationEvent(
            operator, Action.USER_CREATE, "user", target.toString(), Outcome.SUCCESS, null));

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    assertThat(event.getFormattedMessage()).isEqualTo("uam.admin.write");
    // traceId 经 MDC 由 JSON 编码器输出为顶层字段
    assertThat(event.getMDCPropertyMap())
        .containsEntry("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(fields(event))
        .containsEntry("ts", "2026-10-23T08:00:00Z")
        .containsEntry("operatorUserId", operatorUser.toString())
        .containsEntry("operatorAccountId", operatorAccount.toString())
        .doesNotContainKey("clientId")
        .containsEntry("action", "user.create")
        .containsEntry("targetType", "user")
        .containsEntry("targetId", target.toString())
        .containsEntry("outcome", "success")
        .doesNotContainKey("reason");
  }

  @Test
  void everyActionHasItsStableName() {
    assertThat(Action.USER_CREATE.value()).isEqualTo("user.create");
    assertThat(Action.USER_DISABLE.value()).isEqualTo("user.disable");
    assertThat(Action.USER_ENABLE.value()).isEqualTo("user.enable");
    assertThat(Action.SESSION_TERMINATE.value()).isEqualTo("session.terminate");
    assertThat(Action.ACCOUNT_PASSWORD_CHANGE.value()).isEqualTo("account.password.change");
    assertThat(Outcome.SUCCESS.value()).isEqualTo("success");
    assertThat(Outcome.REJECTED.value()).isEqualTo("rejected");
    assertThat(Outcome.FAILED.value()).isEqualTo("failed");
  }

  @Test
  void rejectedOperationsAreLoggedWithTheReasonCategory() {
    writeLog.record(
        new WriteOperationEvent(
            operator,
            Action.USER_DISABLE,
            "user",
            operatorUser.toString(),
            Outcome.REJECTED,
            "cannot_disable_self"));

    assertThat(appender.list).hasSize(1);
    assertThat(fields(appender.list.get(0)))
        .containsEntry("outcome", "rejected")
        .containsEntry("reason", "cannot_disable_self")
        .containsEntry("action", "user.disable");
  }

  @Test
  void failedOperationsAreLoggedAtWarn() {
    writeLog.record(
        new WriteOperationEvent(
            operator,
            Action.SESSION_TERMINATE,
            "session",
            sessionRef.toString(),
            Outcome.FAILED,
            "redis_unavailable"));

    assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    assertThat(fields(appender.list.get(0))).containsEntry("outcome", "failed");
  }

  @Test
  void operationsWithoutAnOperatorAreStillLogged() {
    writeLog.record(
        new WriteOperationEvent(
            null, Action.USER_CREATE, "user", null, Outcome.REJECTED, "operator_required"));

    assertThat(appender.list).hasSize(1);
    assertThat(fields(appender.list.get(0)))
        .containsEntry("reason", "operator_required")
        .doesNotContainKey("clientId")
        .doesNotContainKeys("operatorUserId", "operatorAccountId", "targetId", "traceId");
  }

  @Test
  void partialOperatorContextLogsOnlyTheKnownIds() {
    writeLog.record(
        new WriteOperationEvent(
            new OperatorContext(null, null, sessionRef),
            Action.SESSION_TERMINATE,
            "session",
            sessionRef.toString(),
            Outcome.SUCCESS,
            null));

    assertThat(fields(appender.list.get(0)))
        .doesNotContainKeys("operatorUserId", "operatorAccountId");
  }

  @Test
  void secretsInReasonAndTargetNeverReachTheLog() {
    String password = "Sup3rSecretPassw0rd!";
    String hash = "$argon2id$v=19$m=16384,t=2,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";
    String sessionId = "dGhpc19pc19hX2Zha2Vfc2Vzc2lvbl9pZF9mb3JfdGVzdGluZ19vbmx5X3h4";
    String serviceKey = "uamk_test_fake_key_do_not_use_anywhere";

    writeLog.record(
        new WriteOperationEvent(
            operator,
            Action.ACCOUNT_PASSWORD_CHANGE,
            "account",
            hash,
            Outcome.FAILED,
            "password=" + password + " sessionId=" + sessionId + " key=" + serviceKey));

    ILoggingEvent event = appender.list.get(0);
    String everything = event.getFormattedMessage() + fields(event);
    assertThat(everything)
        .doesNotContain(password)
        .doesNotContain(hash)
        .doesNotContain("argon2id")
        .doesNotContain(sessionId)
        .doesNotContain(serviceKey);
  }
}
