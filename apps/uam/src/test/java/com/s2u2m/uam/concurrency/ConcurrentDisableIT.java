package com.s2u2m.uam.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.UserFixtures;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * T111：并发停用（{@code pg_advisory_xact_lock} 串行化）——两个运维人员同时停用对方、并发停用最后两个正常用户，始终至少留一个 {@code ACTIVE}
 * 用户，输家得到 409 {@code cannot_disable_last_active_user}。
 *
 * <p>测试用栅栏让请求同时发出，并重复多轮；没有锁时「各自都看到还有另一个正常用户」会让两个请求都成功。
 */
class ConcurrentDisableIT extends IntegrationTestBase {

  @Value("${local.server.port}")
  int port;

  private ApiClient api;
  private UserFixtures data;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    data = new UserFixtures(jdbc);
  }

  private Response disable(UUID target, UUID operatorUser) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Operator-User-Id", operatorUser.toString());
    headers.put("X-Operator-Account-Id", UUID.randomUUID().toString());
    headers.put("X-Operator-Session-Ref", UUID.randomUUID().toString());
    return api.postJson("/v1/users/" + target + "/disable", Map.of(), headers);
  }

  private List<Response> concurrently(List<Callable<Response>> requests) throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(requests.size());
    List<Future<Response>> futures = new ArrayList<>();
    for (Callable<Response> request : requests) {
      futures.add(
          pool.submit(
              () -> {
                gate.await();
                return request.call();
              }));
    }
    gate.countDown();
    List<Response> responses = new ArrayList<>();
    for (Future<Response> future : futures) {
      responses.add(future.get(60, TimeUnit.SECONDS));
    }
    pool.shutdown();
    return responses;
  }

  private long active() {
    return jdbc.queryForObject("SELECT count(*) FROM app_user WHERE status = 'ACTIVE'", Long.class);
  }

  private static long count(List<Response> responses, int status) {
    return responses.stream().filter(r -> r.status() == status).count();
  }

  private static void assertAllConflictsAreLastActiveUser(List<Response> responses) {
    responses.stream()
        .filter(r -> r.status() == 409)
        .forEach(
            r ->
                assertThat(r.json().get("code").asString())
                    .isEqualTo("cannot_disable_last_active_user"));
  }

  @RepeatedTest(10)
  void twoOperatorsDisablingEachOtherLeaveExactlyOneActiveUser() throws Exception {
    UUID alice = data.user("甲");
    UUID bob = data.user("乙");

    List<Response> responses =
        concurrently(List.of(() -> disable(bob, alice), () -> disable(alice, bob)));

    assertThat(count(responses, 200)).isEqualTo(1);
    assertThat(count(responses, 409)).isEqualTo(1);
    assertAllConflictsAreLastActiveUser(responses);
    assertThat(active()).isEqualTo(1);
  }

  @RepeatedTest(10)
  void concurrentlyDisablingTheLastTwoActiveUsersLeavesOne() throws Exception {
    UUID first = data.user("甲");
    UUID second = data.user("乙");
    UUID outsider = UUID.randomUUID();

    List<Response> responses =
        concurrently(List.of(() -> disable(first, outsider), () -> disable(second, outsider)));

    assertThat(count(responses, 200)).isEqualTo(1);
    assertThat(count(responses, 409)).isEqualTo(1);
    assertAllConflictsAreLastActiveUser(responses);
    assertThat(active()).isEqualTo(1);
  }

  @Test
  void disablingManyUsersAtOnceAlwaysKeepsOneAndDuplicateRequestsStayIdempotent() throws Exception {
    List<UUID> users = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      users.add(data.user("用户" + i));
    }
    UUID outsider = UUID.randomUUID();
    List<Callable<Response>> requests = new ArrayList<>();
    for (UUID user : users) {
      requests.add(() -> disable(user, outsider));
      requests.add(() -> disable(user, outsider));
    }

    List<Response> responses = concurrently(requests);

    assertThat(active()).isEqualTo(1);
    assertAllConflictsAreLastActiveUser(responses);
    assertThat(responses).allMatch(r -> r.status() == 200 || r.status() == 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE status = 'DISABLED'", Long.class))
        .isEqualTo(5);
  }
}
