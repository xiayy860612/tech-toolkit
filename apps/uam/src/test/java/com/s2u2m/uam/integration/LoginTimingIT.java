package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.application.AccountProvisioningService.Provisioned;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** T051：账号不存在、密码错误、账号 / 用户已停用的响应耗时无可区分差别（都走一次 Argon2 比对，不存在时用伪哈希）。 */
class LoginTimingIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final int SAMPLES = 24;

  /** 中位数相对差的容忍度：Argon2 比对约几十毫秒，差别应远小于一次比对本身。 */
  private static final double TOLERANCE = 0.25;

  @Value("${local.server.port}")
  int port;

  @Autowired AccountProvisioningService provisioning;

  private double medianMillis(ApiClient api, IntFunction<ApiClient.Response> attempt) {
    List<Double> millis = new ArrayList<>();
    for (int i = 0; i < SAMPLES; i++) {
      long start = System.nanoTime();
      ApiClient.Response response = attempt.apply(i);
      millis.add((System.nanoTime() - start) / 1_000_000.0);
      assertThat(response.status()).isEqualTo(401);
    }
    Collections.sort(millis);
    return millis.get(millis.size() / 2);
  }

  @Test
  void unknownAccountAndWrongPasswordAreIndistinguishableByTiming() {
    provisioning.createUserWithAccount("爱丽丝", "alice", PASSWORD);
    Provisioned bob = provisioning.createUserWithAccount("鲍勃", "bob", PASSWORD);
    jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", bob.userId());
    ApiClient api = new ApiClient(port, TEST_SERVICE_KEY);
    // 每次用不同来源地址，避免被限流改变路径；先预热 JIT 与连接
    for (int i = 0; i < 6; i++) {
      api.login("nobody", PASSWORD, "192.0.2." + i);
      api.login("alice", "Wrong-password-1", "192.0.2." + i);
    }

    double unknown = medianMillis(api, i -> api.login("nobody-" + i, PASSWORD, "10.0.0." + i));
    double wrongPassword =
        medianMillis(api, i -> api.login("alice", "Wrong-password-1", "10.0.1." + i));
    double disabledUser = medianMillis(api, i -> api.login("bob", PASSWORD, "10.0.2." + i));

    assertThat(relativeDifference(unknown, wrongPassword))
        .as("不存在 %.1fms vs 密码错误 %.1fms", unknown, wrongPassword)
        .isLessThan(TOLERANCE);
    assertThat(relativeDifference(disabledUser, wrongPassword))
        .as("用户已停用 %.1fms vs 密码错误 %.1fms", disabledUser, wrongPassword)
        .isLessThan(TOLERANCE);
  }

  private static double relativeDifference(double a, double b) {
    return Math.abs(a - b) / Math.max(a, b);
  }
}
