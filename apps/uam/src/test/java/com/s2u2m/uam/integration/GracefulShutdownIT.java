package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.UamInstance;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * T159：优雅停机（FR-039、SC-012）——被停掉的实例在停机期间先变为「未就绪」，在途请求仍然完成。
 *
 * <p>在一个真实启动的 UAM 实例（独立上下文、真实 Tomcat）上，用一个会阻塞到测试放行的探测路由造出「在途请求」，再在后台关闭该实例： 所有等待都由闩锁与条件轮询驱动，没有固定的
 * sleep。探测路由是纯 Bean 来源（没有组件注解），只被这个测试显式注册，不会进入其他测试的上下文。
 */
class GracefulShutdownIT extends IntegrationTestBase {

  /** 探测路由用到的两个闩锁：请求进入处理后放行 {@code entered}，等测试放行 {@code release} 才返回。 */
  static final class HoldRoute {
    static final CountDownLatch ENTERED = new CountDownLatch(1);
    static final CountDownLatch RELEASE = new CountDownLatch(1);

    @Bean
    RouterFunction<ServerResponse> holdRoute() {
      return RouterFunctions.route()
          .GET(
              "/v1/test/hold",
              request -> {
                ENTERED.countDown();
                try {
                  if (!RELEASE.await(30, TimeUnit.SECONDS)) {
                    return ServerResponse.status(500).body("not released");
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return ServerResponse.ok().body("held-and-completed");
              })
          .build();
    }
  }

  @Autowired Environment env;

  @Test
  void anInFlightRequestCompletesWhileTheInstanceIsShuttingDownAndReadinessGoesNotReadyFirst()
      throws Exception {
    UamInstance victim = UamInstance.start(env, HoldRoute.class);
    ApiClient api = victim.api(TEST_SERVICE_KEY);
    ApplicationAvailability availability = victim.context().getBean(ApplicationAvailability.class);
    assertThat(api.get("/actuator/health/readiness").status()).isEqualTo(200);
    assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);

    // 1. 一个在途请求进入处理并阻塞
    CompletableFuture<Response> inFlight =
        CompletableFuture.supplyAsync(() -> api.get("/v1/test/hold"));
    assertThat(HoldRoute.ENTERED.await(10, TimeUnit.SECONDS)).as("在途请求已进入处理").isTrue();

    // 2. 在后台关闭实例（这会等待在途请求）
    CompletableFuture<Void> shutdown = CompletableFuture.runAsync(victim::close);

    // 3. 就绪探针先变为未就绪，而此时在途请求仍未完成、停机也未结束
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> availability.getReadinessState() == ReadinessState.REFUSING_TRAFFIC);
    assertThat(inFlight.isDone()).as("就绪变为未就绪时，在途请求仍在处理").isFalse();
    assertThat(shutdown.isDone()).as("停机在等待在途请求").isFalse();

    // 4. 放行：在途请求以 200 完成，随后停机结束
    HoldRoute.RELEASE.countDown();
    Response response = inFlight.get(10, TimeUnit.SECONDS);
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("held-and-completed");
    shutdown.get(30, TimeUnit.SECONDS);
    assertThat(victim.context().isActive()).isFalse();
  }
}
