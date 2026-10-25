package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.s2u2m.uam.infrastructure.logging.ClientRejectionLogger;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.TestSecrets;
import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** T174：被拒绝的调用写结构化日志（来源、时间、原因类别），<b>永不含</b>密钥、哈希或 Authorization 头的任何部分。 */
@ExtendWith(OutputCaptureExtension.class)
class RejectedCallLogIT extends IntegrationTestBase {

  private static final String WRONG_KEY = "uamk_test_fake_key_wrong_for_log_check";

  @Value("${local.server.port}")
  int port;

  private final Logger logger = (Logger) LoggerFactory.getLogger(ClientRejectionLogger.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

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
    event.getKeyValuePairs().forEach(kv -> map.put(kv.key, kv.value));
    return map;
  }

  private List<Map<String, Object>> events() {
    return appender.list.stream().map(RejectedCallLogIT::fields).toList();
  }

  private ApiClient client(String key) {
    return new ApiClient(port, key);
  }

  @Test
  void eachRejectionIsLoggedWithSourceTimeAndReasonCategory() {
    Instant before = Instant.now().minusSeconds(1);
    client(null).get("/v1/users");
    client(WRONG_KEY).get("/v1/users");
    client(REVOKED_SERVICE_KEY).get("/v1/online-users");
    client(READ_ONLY_SERVICE_KEY).postJson("/v1/users", Map.of());
    new ApiClient(port, null).get("/v1/users", Map.of("Authorization", "Basic dXNlcjpwYXNz"));

    List<Map<String, Object>> events = events();
    assertThat(events)
        .extracting(event -> event.get("reason"))
        .containsExactly("missing_key", "unknown_key", "unknown_key", "forbidden", "malformed");
    assertThat(events)
        .extracting(event -> event.get("status"))
        .containsExactly(401, 401, 401, 403, 401);
    for (Map<String, Object> event : events) {
      assertThat(event.get("sourceAddress"))
          .as("来源地址是 TCP 对端")
          .isIn("127.0.0.1", "0:0:0:0:0:0:0:1", InetAddress.getLoopbackAddress().getHostAddress());
      assertThat(Instant.parse((String) event.get("ts")))
          .isAfter(before)
          .isBefore(Instant.now().plusSeconds(1));
      assertThat(event.get("method")).isIn("GET", "POST");
    }
    assertThat(events.get(3))
        .containsEntry("clientId", "reporting")
        .containsEntry("path", "/v1/users");
    assertThat(events.get(0)).doesNotContainKey("clientId");
  }

  @Test
  void aForwardedHeaderSuppliedByTheCallerDoesNotChangeTheLoggedSource() {
    client(null).get("/v1/users", Map.of("X-Forwarded-For", "203.0.113.9"));

    assertThat(events().get(0).get("sourceAddress")).isNotEqualTo("203.0.113.9");
  }

  @Test
  void successfulCallsAreNotLoggedAsRejections() {
    assertThat(client(TEST_SERVICE_KEY).get("/v1/users").status()).isEqualTo(200);

    assertThat(appender.list).isEmpty();
  }

  @Test
  void theLogNeverContainsAKeyItsHashOrAnyPartOfTheAuthorizationHeader(CapturedOutput output) {
    client(WRONG_KEY).get("/v1/users");
    client(REVOKED_SERVICE_KEY).postJson("/v1/auth/login", Map.of());
    client(READ_ONLY_SERVICE_KEY).postJson("/v1/users", Map.of());
    client(null)
        .get("/v1/users", Map.of("Authorization", "Bearer uamk_test_fake_key_in_header_only"));

    // 结构化事件（ListAppender）与真正输出到控制台的 JSON 行，两处都检查
    String structured =
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString()
            + events();
    String rejectedLines =
        output
            .getAll()
            .lines()
            .filter(l -> l.contains("uam.client.rejected"))
            .reduce("", String::concat);
    assertThat(rejectedLines)
        .contains("\"reason\":\"unknown_key\"")
        .contains("\"reason\":\"forbidden\"");
    for (String leaked :
        new String[] {
          WRONG_KEY,
          REVOKED_SERVICE_KEY,
          READ_ONLY_SERVICE_KEY,
          TestSecrets.sha256Hex(WRONG_KEY),
          TestSecrets.sha256Hex(READ_ONLY_SERVICE_KEY),
          TestSecrets.sha256Hex(REVOKED_SERVICE_KEY),
          "uamk_test_fake_key_in_header_only",
          "Bearer",
          "uamk_",
          "sha256:"
        }) {
      assertThat(structured).as("结构化字段含 %s", leaked).doesNotContain(leaked);
      assertThat(rejectedLines).as("控制台日志含 %s", leaked).doesNotContain(leaked);
    }
  }
}
