package tech.toolkit.auth.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests: one shared PostgreSQL container, Flyway runs as the schema
 * owner, the application runs as the restricted {@code auth_app} role, and every test starts from
 * empty tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class PostgresTestBase {

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18-alpine")
          .withDatabaseName("auth")
          .withUsername("auth_owner")
          .withPassword("owner")
          .withInitScript("db/test-init.sql");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "auth_app");
    registry.add("spring.datasource.password", () -> "auth_app");
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  /**
   * JDBC access as the schema owner, for fixtures and for asserting what the app role cannot do.
   */
  protected static JdbcTemplate ownerJdbc() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  @BeforeEach
  void cleanDatabase() {
    JdbcTemplate owner = ownerJdbc();
    String tables =
        String.join(
            ", ",
            owner.queryForList(
                "select tablename from pg_tables where schemaname = 'public'"
                    + " and tablename <> 'flyway_schema_history'",
                String.class));
    if (!tables.isEmpty()) {
      owner.execute("truncate table " + tables + " restart identity cascade");
    }
  }
}
