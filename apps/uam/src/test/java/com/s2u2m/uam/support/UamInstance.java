package com.s2u2m.uam.support;

import com.s2u2m.uam.UamApplication;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 在同一个测试 JVM 里再启动一个完整的 UAM 实例（独立的 Spring 上下文与随机端口），连接与「第一个实例」相同的 PostgreSQL 与 Redis。
 *
 * <p>用来验证多实例行为（FR-037 ~ FR-040）：两个实例除端口外没有任何共享的进程内状态，所以任何跨实例的一致性只能来自 PostgreSQL 与 Redis。
 * 连接属性读自第一个实例的 {@link Environment}（即 {@link IntegrationTestBase} 注入的 Testcontainers 地址）。
 */
public final class UamInstance implements AutoCloseable {

  private final ConfigurableApplicationContext context;
  private final int port;

  private UamInstance(ConfigurableApplicationContext context) {
    this.context = context;
    this.port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
  }

  /** 启动第二个实例；{@code extraSources} 可追加测试专用的 Bean 来源（例如一个会阻塞的探测路由）。 */
  public static UamInstance start(Environment first, Class<?>... extraSources) {
    List<Class<?>> sources = new ArrayList<>();
    sources.add(UamApplication.class);
    sources.addAll(List.of(extraSources));
    SpringApplication application = new SpringApplication(sources.toArray(Class<?>[]::new));
    application.setRegisterShutdownHook(false);
    ConfigurableApplicationContext context =
        application.run(
            "--server.port=0",
            "--spring.main.banner-mode=off",
            "--spring.jmx.enabled=false",
            "--spring.datasource.url=" + first.getRequiredProperty("spring.datasource.url"),
            "--spring.datasource.username="
                + first.getRequiredProperty("spring.datasource.username"),
            "--spring.datasource.password="
                + first.getRequiredProperty("spring.datasource.password"),
            "--spring.data.redis.host=" + first.getRequiredProperty("spring.data.redis.host"),
            "--spring.data.redis.port=" + first.getRequiredProperty("spring.data.redis.port"),
            "--uam.clients=" + first.getRequiredProperty("uam.clients"));
    return new UamInstance(context);
  }

  public int port() {
    return port;
  }

  public ConfigurableApplicationContext context() {
    return context;
  }

  public ApiClient api(String serviceKey) {
    return new ApiClient(port, serviceKey);
  }

  @Override
  public void close() {
    context.close();
  }
}
