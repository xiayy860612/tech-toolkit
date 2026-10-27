package com.s2u2m.uam.support;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成 / 契约 / 并发测试基座：真实 PostgreSQL 16 与 Redis 7（Testcontainers，整个测试 JVM 共用一对容器）， 属性经 {@link
 * DynamicPropertySource} 注入；每个测试前清空业务表与 Redis。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    POSTGRES.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected StringRedisTemplate redis;

  @BeforeEach
  void cleanState() {
    List<String> tables =
        jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
                + " AND tablename IN ('credential', 'account', 'app_user')",
            String.class);
    if (!tables.isEmpty()) {
      jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
    }
    redis.execute(
        (RedisCallback<Object>)
            connection -> {
              connection.serverCommands().flushAll();
              return null;
            });
  }
}
