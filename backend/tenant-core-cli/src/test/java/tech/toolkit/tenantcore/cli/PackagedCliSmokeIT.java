package tech.toolkit.tenantcore.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.support.PostgresTestBase;

/**
 * Runs the packaged executable (bin/tenant-core-cli + target/tenant-core-cli.jar) against a real
 * database. Executed by the `integration-test` phase, after `package` has built the jar.
 */
class PackagedCliSmokeIT extends PostgresTestBase {

  private static final Path MODULE = Path.of(System.getProperty("user.dir"));

  private String run(Map<String, String> env, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = MODULE.resolve("bin/tenant-core-cli").toString();
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
    pb.environment().putAll(env);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    return output;
  }

  @Test
  void packagedCliCreatesATenantAgainstTheDatabase() throws Exception {
    assertThat(new File(MODULE.toFile(), "target/tenant-core-cli.jar")).exists();
    String url = databaseConfig().jdbcUrl(); // jdbc:postgresql://host:port/db
    java.net.URI uri = java.net.URI.create(url.substring("jdbc:".length()));
    Map<String, String> env =
        Map.of(
            "TENANT_CORE_DB_HOST", uri.getHost(),
            "TENANT_CORE_DB_PORT", String.valueOf(uri.getPort()),
            "TENANT_CORE_DB_NAME", uri.getPath().substring(1),
            "TENANT_CORE_DB_OWNER_USER", databaseConfig().ownerUser(),
            "TENANT_CORE_DB_OWNER_PASSWORD", databaseConfig().ownerPassword(),
            "TENANT_CORE_DB_APP_USER", databaseConfig().appUser(),
            "TENANT_CORE_DB_APP_PASSWORD", databaseConfig().appPassword());

    String created = run(env, "tenant", "create", "--slug", "smoke", "--name", "Smoke Corp");
    assertThat(created).contains("created");
    assertThat(count("core_tenant")).isEqualTo(1);
    assertThat(run(env, "tenant", "show", "--tenant", "smoke")).contains("Smoke Corp");
  }
}
