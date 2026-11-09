package tech.toolkit.tenantcore.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import picocli.CommandLine;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.support.PostgresTestBase;

/**
 * SC-005: the initial password never shows up in output, logs, audit or as a stored plain value.
 */
class PasswordLeakIT extends PostgresTestBase {

  private static final String SECRET = "Zx9-unique-secret-passphrase";

  private final CoreComponents core = core();
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

  @BeforeEach
  void captureLogs() {
    logs.start();
    root.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    root.detachAppender(logs);
  }

  private int run(String stdin, String... args) {
    CommandLine cli =
        TenantCoreCli.commandLine(
            () -> core,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            "cli:test@host",
            null);
    return cli.execute(args);
  }

  @Test
  void theInitialPasswordAndItsHashAreNeverEmitted() {
    run("", "tenant", "create", "--slug", "acme", "--name", "Acme Corp");
    run("", "login-method", "enable", "--tenant", "acme", "--type", "password");
    run("", "user", "create", "--tenant", "acme", "--display-name", "Zhang San");
    String userId =
        JdbcClient.create(ownerDataSource())
            .sql("SELECT tenant_user_id FROM uam_user")
            .query(String.class)
            .single();
    int exit =
        run(
            SECRET + "\n",
            "account",
            "create",
            "--tenant",
            "acme",
            "--user",
            userId,
            "--username",
            "admin1",
            "--password-stdin");
    assertThat(exit).isZero();
    run("", "tenant", "show", "--tenant", "acme");

    String hash =
        JdbcClient.create(ownerDataSource())
            .sql("SELECT password_hash FROM uam_credential_password")
            .query(String.class)
            .single();
    String everything = out + "\n" + err + "\n" + String.join("\n", logLines());
    assertThat(everything).doesNotContain(SECRET).doesNotContain(hash).doesNotContain("argon2");

    List<String> auditRows =
        JdbcClient.create(ownerDataSource())
            .sql(
                "SELECT actor || ' ' || action || ' ' || target || ' ' || coalesce(trace_id, '') FROM audit_event")
            .query(String.class)
            .list();
    assertThat(auditRows).isNotEmpty();
    assertThat(String.join("\n", auditRows)).doesNotContain(SECRET).doesNotContain(hash);
  }

  private List<String> logLines() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }
}
