package com.s2u2m.uam.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** T098：并发新建——抢同一账号名只有一个成功；同键重复提交只建一个用户。 */
class ConcurrentCreateUserIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";

  @Value("${local.server.port}")
  int port;

  private long rows(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  private Map<String, String> headers(String key) {
    return Map.of(
        "X-Operator-User-Id", UUID.randomUUID().toString(),
        "X-Operator-Account-Id", UUID.randomUUID().toString(),
        "X-Operator-Session-Ref", UUID.randomUUID().toString(),
        "Idempotency-Key", key);
  }

  private List<Response> runConcurrently(List<Callable<Response>> tasks) throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    List<Future<Response>> futures = new ArrayList<>();
    for (Callable<Response> task : tasks) {
      futures.add(
          pool.submit(
              () -> {
                gate.await();
                return task.call();
              }));
    }
    gate.countDown();
    List<Response> responses = new ArrayList<>();
    for (Future<Response> future : futures) {
      responses.add(future.get());
    }
    pool.shutdown();
    return responses;
  }

  @Test
  void twoRequestsRacingForTheSameAccountNameYieldExactlyOneUser() throws Exception {
    ApiClient api = new ApiClient(port, TEST_SERVICE_KEY);
    List<Callable<Response>> tasks = new ArrayList<>();
    for (String name : List.of("Racer.Name", "racer.name")) {
      tasks.add(
          () ->
              api.postJson(
                  "/v1/users",
                  Map.of("userName", "抢号者", "accountName", name, "password", PASSWORD),
                  headers("key-" + name + "-0123456789")));
    }

    List<Response> responses = runConcurrently(tasks);

    assertThat(responses.stream().map(Response::status)).containsExactlyInAnyOrder(201, 409);
    Response loser = responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
    assertThat(loser.json().get("code").asString()).isEqualTo("account_name_taken");
    assertThat(rows("app_user")).isEqualTo(1);
    assertThat(rows("account")).isEqualTo(1);
    assertThat(rows("credential")).isEqualTo(1);
  }

  @Test
  void manyRequestsForTheSameAccountNameYieldExactlyOneUser() throws Exception {
    ApiClient api = new ApiClient(port, TEST_SERVICE_KEY);
    List<Callable<Response>> tasks = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String key = "distinct-key-000000" + i;
      tasks.add(
          () ->
              api.postJson(
                  "/v1/users",
                  Map.of("userName", "抢号者", "accountName", "shared.name", "password", PASSWORD),
                  headers(key)));
    }

    List<Response> responses = runConcurrently(tasks);

    assertThat(responses.stream().filter(r -> r.status() == 201)).hasSize(1);
    assertThat(responses.stream().filter(r -> r.status() == 409)).hasSize(7);
    assertThat(rows("app_user")).isEqualTo(1);
    assertThat(rows("account")).isEqualTo(1);
    assertThat(rows("credential")).isEqualTo(1);
  }

  @Test
  void aDoubleSubmitWithTheSameKeyCreatesOneUserAndBothGetTheSame201() throws Exception {
    ApiClient api = new ApiClient(port, TEST_SERVICE_KEY);
    String key = "double-click-key-0123456789";
    List<Callable<Response>> tasks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      tasks.add(
          () ->
              api.postJson(
                  "/v1/users",
                  Map.of("userName", "双击", "accountName", "double.click", "password", PASSWORD),
                  headers(key)));
    }

    List<Response> responses = runConcurrently(tasks);

    assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
    assertThat(responses.stream().map(r -> r.json().get("id").asString()).distinct()).hasSize(1);
    assertThat(responses.stream().map(r -> r.json().toString()).distinct()).hasSize(1);
    assertThat(rows("app_user")).isEqualTo(1);
    assertThat(rows("account")).isEqualTo(1);
    assertThat(rows("credential")).isEqualTo(1);
  }
}
