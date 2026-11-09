package tech.toolkit.tenantcore.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import picocli.CommandLine;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.support.PostgresTestBase;

/** Contract tests for contracts/cli.md: exit codes, idempotency, output shape. */
class CliContractIT extends PostgresTestBase {

  private static final String PW = "correct horse battery\n";

  private final CoreComponents core = core();

  record Result(int exit, String out, String err) {}

  private Result run(String stdin, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    CommandLine cli =
        TenantCoreCli.commandLine(
            () -> core,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            "cli:test@host",
            null);
    int exit = cli.execute(args);
    return new Result(
        exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private Result cli(String... args) {
    return run("", args);
  }

  private String createUser(String tenant, String displayName) {
    Result r =
        cli(
            "--output",
            "json",
            "user",
            "create",
            "--tenant",
            tenant,
            "--display-name",
            displayName);
    assertThat(r.exit()).isZero();
    Matcher m = Pattern.compile("\"tenantUserId\":\"(tu_[a-z0-9]+)\"").matcher(r.out());
    assertThat(m.find()).as(r.out()).isTrue();
    return m.group(1);
  }

  private void bootstrapAcme() {
    assertThat(cli("tenant", "create", "--slug", "acme", "--name", "Acme Corp").exit()).isZero();
    assertThat(cli("login-method", "enable", "--tenant", "acme", "--type", "password").exit())
        .isZero();
    String id = createUser("acme", "Zhang San");
    assertThat(
            run(
                    PW,
                    "account",
                    "create",
                    "--tenant",
                    "acme",
                    "--user",
                    id,
                    "--username",
                    "admin1",
                    "--password-stdin")
                .exit())
        .isZero();
    assertThat(cli("subscription", "add", "--tenant", "acme", "--module", "platform").exit())
        .isZero();
  }

  @Test
  void bootstrapsATenantEndToEnd() {
    bootstrapAcme();
    Result show = cli("tenant", "show", "--tenant", "acme");
    assertThat(show.exit()).isZero();
    assertThat(show.out())
        .contains("acme", "Acme Corp", "PASSWORD", "Zhang San", "admin1", "platform")
        .doesNotContain("argon2");
  }

  @Test
  void textOutputNamesTheResult() {
    assertThat(cli("tenant", "create", "--slug", "acme", "--name", "Acme Corp").out())
        .contains("created");
    assertThat(cli("tenant", "create", "--slug", "acme", "--name", "Acme Corp").out())
        .contains("exists");
  }

  @Test
  void jsonOutputIsASingleLineWithTheResult() {
    Result r = cli("--output", "json", "tenant", "create", "--slug", "acme", "--name", "Acme Corp");
    assertThat(r.exit()).isZero();
    assertThat(r.out().trim()).doesNotContain("\n").startsWith("{").endsWith("}");
    assertThat(r.out()).contains("\"result\":\"created\"").contains("\"tenant\":\"acme\"");
    Result again =
        cli("--output", "json", "tenant", "create", "--slug", "acme", "--name", "Acme Corp");
    assertThat(again.out()).contains("\"result\":\"exists\"");
  }

  @Test
  void repeatingEveryWriteCommandChangesNothing() {
    bootstrapAcme();
    long tenants = count("core_tenant");
    long methods = count("uam_tenant_login_method");
    long users = count("uam_user");
    long accounts = count("uam_account");
    long subscriptions = count("core_module_subscription");
    String password =
        JdbcClient.create(ownerDataSource())
            .sql("SELECT password_hash FROM uam_credential_password")
            .query(String.class)
            .single();

    assertThat(cli("tenant", "create", "--slug", "acme", "--name", "Acme Corp").exit()).isZero();
    assertThat(cli("login-method", "enable", "--tenant", "acme", "--type", "password").out())
        .contains("already_enabled");
    String again = createUser("acme", "Zhang San");
    assertThat(
            run(
                    "another password 123\n",
                    "account",
                    "create",
                    "--tenant",
                    "acme",
                    "--user",
                    again,
                    "--username",
                    "admin1",
                    "--password-stdin")
                .exit())
        .isZero();
    assertThat(cli("subscription", "add", "--tenant", "acme", "--module", "platform").out())
        .contains("already_subscribed");

    assertThat(count("core_tenant")).isEqualTo(tenants);
    assertThat(count("uam_tenant_login_method")).isEqualTo(methods);
    assertThat(count("uam_user")).isEqualTo(users);
    assertThat(count("uam_account")).isEqualTo(accounts);
    assertThat(count("core_module_subscription")).isEqualTo(subscriptions);
    assertThat(
            JdbcClient.create(ownerDataSource())
                .sql("SELECT password_hash FROM uam_credential_password")
                .query(String.class)
                .single())
        .isEqualTo(password);
  }

  @Test
  void thereIsNoPasswordOption() {
    cli("tenant", "create", "--slug", "acme", "--name", "Acme Corp");
    cli("login-method", "enable", "--tenant", "acme", "--type", "password");
    String id = createUser("acme", "Zhang San");
    Result r =
        cli(
            "account",
            "create",
            "--tenant",
            "acme",
            "--user",
            id,
            "--username",
            "admin1",
            "--password",
            "correct horse battery");
    assertThat(r.exit()).isEqualTo(1);
    assertThat(r.err()).contains("Unknown option").doesNotContain("correct horse battery");
    assertThat(count("uam_account")).isZero();
  }

  @Test
  void validationErrorsExitWith1() {
    assertThat(cli("tenant", "create", "--slug", "Bad_Slug", "--name", "Acme").exit()).isEqualTo(1);
    cli("tenant", "create", "--slug", "acme", "--name", "Acme Corp");
    assertThat(cli("login-method", "enable", "--tenant", "acme", "--type", "oidc").exit())
        .isEqualTo(1);
    assertThat(cli("user", "create", "--tenant", "acme", "--display-name", " ").exit())
        .isEqualTo(1);
    assertThat(cli("tenant", "show").exit()).isEqualTo(1); // missing required option
  }

  @Test
  void missingPreconditionsExitWith2AndWriteNothing() {
    assertThat(cli("login-method", "enable", "--tenant", "nope", "--type", "password").exit())
        .isEqualTo(2);
    assertThat(cli("user", "create", "--tenant", "nope", "--display-name", "X Y Z").exit())
        .isEqualTo(2);
    assertThat(cli("subscription", "add", "--tenant", "nope", "--module", "platform").exit())
        .isEqualTo(2);
    assertThat(cli("tenant", "show", "--tenant", "nope").exit()).isEqualTo(2);

    cli("tenant", "create", "--slug", "acme", "--name", "Acme Corp");
    assertThat(cli("subscription", "add", "--tenant", "acme", "--module", "billing").exit())
        .isEqualTo(2);
    String id = createUser("acme", "Zhang San");
    assertThat(
            run(
                    PW,
                    "account",
                    "create",
                    "--tenant",
                    "acme",
                    "--user",
                    id,
                    "--username",
                    "admin1",
                    "--password-stdin")
                .exit())
        .as("password login not enabled")
        .isEqualTo(2);
    assertThat(count("uam_account")).isZero();
    assertThat(count("uam_credential_password")).isZero();
    assertThat(count("core_module_subscription")).isZero();
  }

  @Test
  void conflictsExitWith3() {
    cli("tenant", "create", "--slug", "acme", "--name", "Acme Corp");
    assertThat(cli("tenant", "create", "--slug", "acme", "--name", "Another").exit()).isEqualTo(3);
    cli("login-method", "enable", "--tenant", "acme", "--type", "password");
    String first = createUser("acme", "Zhang San");
    String second = createUser("acme", "Li Si");
    run(
        PW,
        "account",
        "create",
        "--tenant",
        "acme",
        "--user",
        first,
        "--username",
        "admin1",
        "--password-stdin");
    assertThat(
            run(
                    PW,
                    "account",
                    "create",
                    "--tenant",
                    "acme",
                    "--user",
                    second,
                    "--username",
                    "admin1",
                    "--password-stdin")
                .exit())
        .isEqualTo(3);
  }

  @Test
  void weakOrEmptyPasswordsAreRejectedWithoutPartialWrites() {
    cli("tenant", "create", "--slug", "acme", "--name", "Acme Corp");
    cli("login-method", "enable", "--tenant", "acme", "--type", "password");
    String id = createUser("acme", "Zhang San");
    assertThat(
            run(
                    "short\n",
                    "account",
                    "create",
                    "--tenant",
                    "acme",
                    "--user",
                    id,
                    "--username",
                    "admin1",
                    "--password-stdin")
                .exit())
        .isEqualTo(1);
    assertThat(
            run(
                    "",
                    "account",
                    "create",
                    "--tenant",
                    "acme",
                    "--user",
                    id,
                    "--username",
                    "admin1",
                    "--password-stdin")
                .exit())
        .isEqualTo(1);
    assertThat(count("uam_account")).isZero();
    assertThat(count("uam_credential_password")).isZero();
  }

  @Test
  void showInJsonListsTheTenantItsUsersAndAccounts() {
    bootstrapAcme();
    Result r = cli("--output", "json", "tenant", "show", "--tenant", "acme");
    assertThat(r.exit()).isZero();
    assertThat(r.out().trim()).doesNotContain("\n");
    assertThat(r.out())
        .contains("\"result\":\"ok\"")
        .contains("\"tenant\":\"acme\"")
        .contains("\"loginMethods\":[\"PASSWORD\"]")
        .contains("\"displayName\":\"Zhang San\"")
        .contains("\"accountUsernames\":[\"admin1\"]")
        .contains("\"modules\":[\"platform\"]")
        .doesNotContain("argon2");
  }

  @Test
  void jsonOutputEscapesSpecialCharacters() {
    Result r =
        cli(
            "--output",
            "json",
            "tenant",
            "create",
            "--slug",
            "acme",
            "--name",
            "Say \"hi\" \\ a\tb\u0001c");
    assertThat(r.exit()).isZero();
    assertThat(r.out()).contains("\\\"hi\\\"").contains("\\\\").contains("\\t").contains("\\u0001");
  }

  @Test
  void anUnknownOutputFormatIsRejected() {
    Result r = cli("--output", "xml", "tenant", "create", "--slug", "acme", "--name", "Acme Corp");
    assertThat(r.exit()).isEqualTo(1);
    assertThat(r.err()).contains("--output");
    assertThat(count("core_tenant")).isZero();
  }

  @Test
  void aMissingCommandIsAnError() {
    assertThat(cli().exit()).isEqualTo(1);
    assertThat(cli("tenant").exit()).isEqualTo(1);
  }

  @Test
  void onlyPasswordAccountsAreSupported() {
    cli("tenant", "create", "--slug", "acme", "--name", "Acme Corp");
    cli("login-method", "enable", "--tenant", "acme", "--type", "password");
    String id = createUser("acme", "Zhang San");
    Result r =
        run(
            PW,
            "account",
            "create",
            "--tenant",
            "acme",
            "--user",
            id,
            "--username",
            "admin1",
            "--type",
            "oidc",
            "--password-stdin");
    assertThat(r.exit()).isEqualTo(1);
    assertThat(count("uam_account")).isZero();
  }

  @Test
  void auditActorIsTheCliOperator() {
    bootstrapAcme();
    assertThat(
            JdbcClient.create(ownerDataSource())
                .sql("SELECT DISTINCT actor FROM audit_event")
                .query(String.class)
                .list())
        .containsExactly("cli:test@host");
  }
}
