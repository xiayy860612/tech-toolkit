package tech.toolkit.tenantcore.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.Clocks;
import tech.toolkit.tenantcore.common.DatabaseConfig;
import tech.toolkit.tenantcore.tenant.Tenant;
import tech.toolkit.tenantcore.uam.Account;
import tech.toolkit.tenantcore.uam.UamSettings;
import tech.toolkit.tenantcore.uam.User;

/**
 * Starts one PostgreSQL container per JVM, runs the Flyway migrations as the owner role and exposes
 * an owner and an application (restricted) DataSource. The database is emptied before every test.
 */
public abstract class PostgresTestBase {

  public static final String APP_USER = "tenant_core_app";
  public static final String APP_PASSWORD = "test-app";

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18").withInitScript("db/test-roles.sql");
  private static final DatabaseConfig CONFIG;
  private static final DataSource OWNER;
  private static final DataSource APP;

  static {
    POSTGRES.start();
    CONFIG =
        new DatabaseConfig(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            APP_USER,
            APP_PASSWORD);
    CONFIG.migrate();
    OWNER = CONFIG.ownerDataSource();
    APP = CONFIG.appDataSource();
  }

  protected static DatabaseConfig databaseConfig() {
    return CONFIG;
  }

  protected static DataSource ownerDataSource() {
    return OWNER;
  }

  protected static DataSource appDataSource() {
    return APP;
  }

  public static final String PASSWORD = "correct horse battery";

  /**
   * Tenant + User + password Account created through the real services (plus a platform
   * subscription).
   */
  public record Bootstrapped(Tenant tenant, User user, Account account) {}

  protected static CoreComponents core() {
    return core(Clocks.systemUtc());
  }

  protected static CoreComponents core(Clock clock) {
    return new CoreComponents(appDataSource(), clock, UamSettings.ephemeral());
  }

  /** A clock tests can move forward to exercise expiry and lockout. */
  public static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-07T08:00:00Z");

    public void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  protected static Bootstrapped bootstrap(CoreComponents core, String slug, String username) {
    Tenant tenant = core.tenants().create(slug, "Name " + slug, "test").tenant();
    core.loginMethods().enable(slug, "password", "test");
    User user = core.users().create(slug, "User " + username, "test").user();
    Account account =
        core.accounts()
            .createPasswordAccount(slug, user.tenantUserId(), username, PASSWORD, "test")
            .account();
    core.subscriptions().add(slug, "platform", "test");
    return new Bootstrapped(tenant, user, account);
  }

  protected static List<String> auditOutcomes(String action) {
    return JdbcClient.create(OWNER)
        .sql("SELECT outcome FROM audit_event WHERE action = ? ORDER BY occurred_at, id")
        .param(action)
        .query(String.class)
        .list();
  }

  protected static long count(String table) {
    return JdbcClient.create(OWNER).sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  @BeforeEach
  void cleanDatabase() {
    JdbcClient.create(OWNER)
        .sql(
            "TRUNCATE audit_event, uam_opaque_token, uam_credential_password, uam_account,"
                + " uam_user, uam_tenant_login_method, core_module_subscription, core_tenant"
                + " CASCADE")
        .update();
  }
}
