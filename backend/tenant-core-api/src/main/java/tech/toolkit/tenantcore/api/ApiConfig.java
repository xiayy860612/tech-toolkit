package tech.toolkit.tenantcore.api;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DatabaseConfig;
import tech.toolkit.tenantcore.uam.UamSettings;

/** Builds the shared business components once and exposes them to the controllers. */
@Configuration
class ApiConfig {

  @Bean
  DatabaseConfig databaseConfig(Environment env) {
    String url = env.getProperty("tenant-core.db.jdbc-url");
    if (url == null || url.isBlank()) {
      url =
          "jdbc:postgresql://"
              + env.getProperty("tenant-core.db.host", "localhost")
              + ":"
              + env.getProperty("tenant-core.db.port", "5432")
              + "/"
              + env.getProperty("tenant-core.db.name", "tenant_core");
    }
    return new DatabaseConfig(
        url,
        env.getProperty("tenant-core.db.owner-user", "tenant_core"),
        env.getRequiredProperty("tenant-core.db.owner-password"),
        env.getProperty("tenant-core.db.app-user", "tenant_core_app"),
        env.getRequiredProperty("tenant-core.db.app-password"));
  }

  /**
   * Migrates as the owner role at start-up, then serves requests as the restricted application
   * role.
   */
  @Bean(destroyMethod = "close")
  HikariDataSource appDataSource(DatabaseConfig config) {
    config.migrate();
    return config.appDataSource();
  }

  @Bean
  UamSettings uamSettings(Environment env) {
    return new UamSettings(
        env.getRequiredProperty("uam.issuer"),
        env.getRequiredProperty("uam.audience"),
        env.getRequiredProperty("uam.cell-id"),
        env.getRequiredProperty("uam.jwt-ttl-seconds", Integer.class),
        env.getProperty("uam.signing-key-pem"),
        env.acceptsProfiles(Profiles.of("dev", "test")));
  }

  @Bean
  CoreComponents coreComponents(DataSource dataSource, UamSettings settings) {
    return new CoreComponents(dataSource, Clock.systemUTC(), settings);
  }
}
