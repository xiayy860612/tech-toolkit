package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.model.OperatorContext;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.port.WriteOperationLog;
import com.s2u2m.uam.infrastructure.redis.ReplicaWait;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;

/** T022 / T025 / T028 的装配验证：服务密钥过滤器已生效、就绪探针依赖 PostgreSQL 与 Redis、WAIT 辅助方法可用。 */
@ExtendWith(OutputCaptureExtension.class)
class FoundationIT extends IntegrationTestBase {

  @Value("${local.server.port}")
  int port;

  @Autowired ReplicaWait replicaWait;
  @Autowired Environment env;
  @Autowired WriteOperationLog writeLog;

  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(String path, String authorization) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void requestsWithoutTheServiceKeyAreRejectedAsProblemJson() throws Exception {
    HttpResponse<String> response = get("/v1/users", null);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
    assertThat(response.body()).contains("\"code\":\"client_unauthenticated\"");
    assertThat(response.headers().firstValue("traceparent")).isPresent();
  }

  @Test
  void requestsWithAValidKeyReachTheDispatcher() throws Exception {
    HttpResponse<String> response = get("/v1/does-not-exist", "Bearer " + TEST_SERVICE_KEY);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
  }

  @Test
  void readinessProbeIsUpAndNeedsNoServiceKey() throws Exception {
    HttpResponse<String> response = get("/actuator/health/readiness", null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void livenessProbeIsUp() throws Exception {
    assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
  }

  @Test
  void readinessGroupDependsOnPostgresAndRedis() {
    // 不为触发 DOWN 而破坏共享容器，改为断言 application.yml 的 readiness 组配置
    assertThat(env.getProperty("management.endpoint.health.group.readiness.include"))
        .contains("db")
        .contains("redis");
  }

  @Test
  void waitHelperReturnsImmediatelyOnASingleMasterWithoutReplicas() {
    redis.opsForValue().set("uam:test:wait", "1");

    long start = System.nanoTime();
    long acknowledged = replicaWait.await(Duration.ofMillis(200));

    assertThat(acknowledged).isZero();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void writeOperationLogIsStructuredJsonWithTopLevelFieldsAndRedaction(CapturedOutput output) {
    MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
    try {
      writeLog.record(
          new WriteOperationEvent(
              new OperatorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
              "admin-center",
              Action.USER_DISABLE,
              "user",
              "target-1",
              Outcome.REJECTED,
              "cannot_disable_self password=Sup3rSecret"));
    } finally {
      MDC.clear();
    }

    String line =
        output
            .getAll()
            .lines()
            .filter(l -> l.contains("\"message\":\"uam.admin.write\""))
            .findFirst()
            .orElseThrow();
    assertThat(line)
        .contains("\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\"")
        .contains("\"action\":\"user.disable\"")
        .contains("\"outcome\":\"rejected\"")
        .contains("\"clientId\":\"admin-center\"")
        .doesNotContain("Sup3rSecret");
  }
}
