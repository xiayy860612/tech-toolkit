package com.s2u2m.uam.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.application.AccountProvisioningService.Provisioned;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** T049：同一账号 N 个并发登录，最终恰好一个有效会话（登录脚本原子完成「读旧 → 删旧 → 写新」）。 */
class ConcurrentLoginIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final int LOGINS = 12;

  @Value("${local.server.port}")
  int port;

  @Autowired AccountProvisioningService provisioning;

  @Test
  void exactlyOneSessionSurvivesConcurrentLoginsOfTheSameAccount() throws Exception {
    Provisioned created = provisioning.createUserWithAccount("张三", "zhangsan", PASSWORD);
    ApiClient api = new ApiClient(port);

    CountDownLatch gate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(LOGINS);
    List<Future<String>> futures = new ArrayList<>();
    for (int i = 0; i < LOGINS; i++) {
      // 每个并发登录用不同的来源地址：同一「账号 + 来源」同时在途的尝试最多 5 个（限流闸门原子占名额，见 ConcurrentThrottleIT）
      String sourceIp = "203.0.113." + (i + 1);
      Callable<String> task =
          () -> {
            gate.await();
            ApiClient.Response response = api.login("zhangsan", PASSWORD, sourceIp);
            assertThat(response.status()).isEqualTo(200);
            return response.json().get("sessionId").asString();
          };
      futures.add(pool.submit(task));
    }
    gate.countDown();
    List<String> sessionIds = new ArrayList<>();
    for (Future<String> future : futures) {
      sessionIds.add(future.get());
    }
    pool.shutdown();

    assertThat(Set.copyOf(sessionIds)).hasSize(LOGINS);
    List<String> valid =
        sessionIds.stream()
            .filter(id -> api.verify(id).status() == 200)
            .collect(Collectors.toList());
    assertThat(valid).as("恰好一个有效会话").hasSize(1);

    // Redis 里也只剩一套索引，且都指向这个会话
    assertThat(redis.keys("uam:sess:*")).hasSize(1);
    assertThat(redis.keys("uam:sref:*")).hasSize(1);
    assertThat(redis.keys("uam:acct:*"))
        .containsExactly("uam:acct:" + created.accountId() + ":sess");
    assertThat(redis.opsForZSet().zCard("uam:online")).isEqualTo(1);
    String survivorRef = api.verify(valid.get(0)).json().get("sessionRef").asString();
    assertThat(redis.keys("uam:sref:*")).containsExactly("uam:sref:" + survivorRef);
  }
}
