package tech.toolkit.tenantcore.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.flywaydb.core.Flyway;

/**
 * Database access settings. The owner role runs Flyway migrations; the application role is used at
 * runtime (it cannot modify audit events). Values come from environment variables, never from the
 * repository.
 */
public record DatabaseConfig(
    String jdbcUrl, String ownerUser, String ownerPassword, String appUser, String appPassword) {

  public static DatabaseConfig fromEnv() {
    return fromEnv(System.getenv());
  }

  public static DatabaseConfig fromEnv(Map<String, String> env) {
    String host = env.getOrDefault("TENANT_CORE_DB_HOST", "localhost");
    String port = env.getOrDefault("TENANT_CORE_DB_PORT", "5432");
    String name = env.getOrDefault("TENANT_CORE_DB_NAME", "tenant_core");
    return new DatabaseConfig(
        "jdbc:postgresql://" + host + ":" + port + "/" + name,
        env.getOrDefault("TENANT_CORE_DB_OWNER_USER", "tenant_core"),
        required(env, "TENANT_CORE_DB_OWNER_PASSWORD"),
        env.getOrDefault("TENANT_CORE_DB_APP_USER", "tenant_core_app"),
        required(env, "TENANT_CORE_DB_APP_PASSWORD"));
  }

  private static String required(Map<String, String> env, String key) {
    String value = env.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required environment variable " + key);
    }
    return value;
  }

  /** Applies all pending Flyway migrations as the owner role. */
  public void migrate() {
    Flyway.configure()
        .dataSource(jdbcUrl, ownerUser, ownerPassword)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  public HikariDataSource ownerDataSource() {
    return pool(ownerUser, ownerPassword, "tenant-core-owner");
  }

  public HikariDataSource appDataSource() {
    return pool(appUser, appPassword, "tenant-core-app");
  }

  private HikariDataSource pool(String user, String password, String poolName) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(user);
    config.setPassword(password);
    config.setPoolName(poolName);
    config.setMaximumPoolSize(8);
    return new HikariDataSource(config);
  }

  @Override
  public String toString() {
    return "DatabaseConfig[jdbcUrl="
        + jdbcUrl
        + ", ownerUser="
        + ownerUser
        + ", appUser="
        + appUser
        + "]";
  }
}
