package com.s2u2m.uam.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.port.PasswordHasher;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 登录与改密的限流在并发下是原子的：同一「账号 + 来源」并发的错误密码尝试，恰好 5 个被判定为凭据错误（401 / 422 {@code
 * current_password_incorrect}），其余一律 429；之后即使密码正确也仍是 429。限流闸门必须在（较慢的）哈希比对之前原子占用名额， 否则 N
 * 个并发尝试都会在计数增加之前通过闸门。
 */
class ConcurrentThrottleIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final String NEW = "Another-fake-2";
  private static final String WRONG = "Wrong-fake-0";
  private static final String IP = "203.0.113.7";
  private static final int ATTEMPTS = 30;
  private static final int MAX_FAILURES = 5;

  @Value("${local.server.port}")
  int port;

  @Autowired PasswordHasher hasher;

  private final ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);

  private List<Integer> fireTogether(Supplier<Response> attempt) throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < ATTEMPTS; i++) {
      Callable<Integer> task =
          () -> {
            gate.await();
            return attempt.get().status();
          };
      futures.add(pool.submit(task));
    }
    gate.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> future : futures) {
      statuses.add(future.get());
    }
    pool.shutdown();
    return statuses;
  }

  @Test
  void parallelWrongPasswordLoginsAdmitExactlyTheMaximumAndBlockTheRest() throws Exception {
    UserFixtures data = new UserFixtures(jdbc);
    UUID accountId = data.account(data.user("张三"), "zhangsan");
    data.credential(accountId, hasher.hash(PASSWORD));
    ApiClient api = new ApiClient(port, TEST_SERVICE_KEY);

    List<Integer> statuses = fireTogether(() -> api.login("zhangsan", WRONG, IP));

    assertThat(statuses.stream().filter(s -> s == 401).count()).isEqualTo(MAX_FAILURES);
    assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(ATTEMPTS - MAX_FAILURES);
    Response correct = api.login("zhangsan", PASSWORD, IP);
    assertThat(correct.status()).isEqualTo(429);
    assertThat(correct.retryAfter()).isPresent();
    assertThat(correct.retryAfter().getAsLong()).isBetween(890L, 900L);
    assertThat(api.login("zhangsan", PASSWORD, "198.51.100.9").status()).isEqualTo(200);
  }

  @Test
  void parallelWrongCurrentPasswordsAdmitExactlyTheMaximumAndBlockTheRest() throws Exception {
    UserFixtures data = new UserFixtures(jdbc);
    UUID userId = data.user("运维甲");
    UUID accountId = data.account(userId, "ops");
    data.credential(accountId, hasher.hash(PASSWORD));
    ApiClient api = new ApiClient(port, TEST_SERVICE_KEY);
    Map<String, String> operator = new LinkedHashMap<>();
    operator.put("X-Operator-User-Id", userId.toString());
    operator.put("X-Operator-Account-Id", accountId.toString());
    operator.put("X-Operator-Session-Ref", UUID.randomUUID().toString());

    List<Integer> statuses =
        fireTogether(
            () ->
                api.postJson(
                    "/v1/accounts/" + accountId + "/password",
                    Map.of("currentPassword", WRONG, "newPassword", NEW, "clientIp", IP),
                    operator));

    assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(MAX_FAILURES);
    assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(ATTEMPTS - MAX_FAILURES);
    Response correct =
        api.postJson(
            "/v1/accounts/" + accountId + "/password",
            Map.of("currentPassword", PASSWORD, "newPassword", NEW, "clientIp", IP),
            operator);
    assertThat(correct.status()).isEqualTo(429);
    assertThat(correct.retryAfter()).isPresent();
  }
}
