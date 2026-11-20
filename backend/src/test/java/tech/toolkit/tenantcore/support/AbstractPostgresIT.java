package tech.toolkit.tenantcore.support;

import javax.sql.DataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 16（Testcontainers）+ Flyway 迁移。迁移用容器的超级用户执行；应用以非属主、非超级用户的 {@code tenantcore_app} 连接，因此
 * Row-Level Security 真正生效。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class AbstractPostgresIT {

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16").withInitScript("test-init.sql");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "tenantcore_app");
    registry.add("spring.datasource.password", () -> "tenantcore-test-only");
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("admin-center.jwt.public-key", () -> TestKeys.publicKeyPem());
  }

  /** 超级用户数据源（绕过 RLS），仅用于测试里直接检查表约束与触发器。 */
  protected static DataSource ownerDataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
