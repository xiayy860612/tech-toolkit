package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.cli.CliRunner;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T066：部署期命令在 {@code cli} profile 下端到端运行——只连 PostgreSQL（不启动 Web、不连 Redis）， 先跑
 * Flyway，密码经标准输入，标准输出只给结果、日志写标准错误。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    args = {"init-admin", "--account-name", "admin", "--user-name", "系统管理员"})
@ActiveProfiles("cli")
@ExtendWith(OutputCaptureExtension.class)
class CliProfileIT {

  private static final String PASSWORD = "Passw0rd-cli-fake-1";
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
  private static InputStream originalIn;

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @BeforeAll
  static void feedThePasswordOnStdin() {
    originalIn = System.in;
    System.setIn(new ByteArrayInputStream((PASSWORD + "\n").getBytes(StandardCharsets.UTF_8)));
  }

  @AfterAll
  static void restoreStdin() {
    System.setIn(originalIn);
  }

  @Autowired ApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired CliRunner runner;

  @Test
  void runsFlywayThenCreatesTheFirstUserWithoutWebOrRedis(CapturedOutput output) {
    assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
    assertThat(
            context.getBeanNamesForType(
                org.springframework.data.redis.core.StringRedisTemplate.class))
        .isEmpty();
    assertThat(runner.getExitCode()).isZero();

    Map<String, Object> user = jdbc.queryForMap("SELECT id, name FROM app_user");
    assertThat(user).containsEntry("name", "系统管理员");
    assertThat(jdbc.queryForObject("SELECT login_name FROM account", String.class))
        .isEqualTo("admin");
    String hash = jdbc.queryForObject("SELECT password_hash FROM credential", String.class);
    assertThat(hash).startsWith("{argon2@SpringSecurity_v5_8}$argon2id$").doesNotContain(PASSWORD);

    // 命令的结果（用户标识）在标准输出，人读信息在标准错误；日志写哪个流由 logback-spring.xml 的 cli 段决定，
    // 测试容器里 profile 在日志初始化之后才生效，所以这里不断言日志流，真实 jar 的流分离见手工冒烟验证
    assertThat(output.getOut()).contains(user.get("id").toString());
    assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(hash);
    assertThat(output.getErr()).contains("admin");
  }

  @Test
  void webOnlyBeansAreNotLoadedUnderTheCliProfile() {
    for (String name : new String[] {"authController", "loginUseCase"}) {
      assertThat(context.containsBean(name)).as(name).isFalse();
    }
    org.junit.jupiter.api.Assertions.assertThrows(
        NoSuchBeanDefinitionException.class,
        () -> context.getBean(com.s2u2m.uam.domain.port.SessionStore.class));
  }
}
