package com.s2u2m.uam.unit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.cli.StandaloneCommands;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * T181：{@code generate-service-key} 在启动 Spring 之前由 {@link StandaloneCommands} 直接执行——没有数据源、没有
 * Flyway、没有 Redis， 镜像里不配数据库也能用；其余命令不受影响（返回空，交给 Spring 的 {@code cli} profile）。
 */
class StandaloneCommandsTest {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private OptionalInt tryRun(String... args) {
    return StandaloneCommands.tryRun(
        args,
        new ByteArrayInputStream(new byte[0]),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @Test
  void generateServiceKeyRunsWithoutAnySpringContext() {
    OptionalInt code = tryRun("generate-service-key", "--client", "admin-center");

    assertThat(code).hasValue(0);
    assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("uamk_").endsWith("\n");
  }

  @Test
  void usageErrorsOfTheStandaloneCommandKeepTheCliExitCode() {
    assertThat(tryRun("generate-service-key")).hasValue(2);
    assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
  }

  @Test
  void otherCommandsAndNormalStartupAreLeftToSpring() {
    assertThat(tryRun("init-admin", "--account-name", "admin")).isEmpty();
    assertThat(tryRun("add-account")).isEmpty();
    assertThat(tryRun()).isEmpty();
    assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
  }
}
