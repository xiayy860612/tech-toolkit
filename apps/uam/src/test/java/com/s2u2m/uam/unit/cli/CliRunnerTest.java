package com.s2u2m.uam.unit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.cli.CliCommand;
import com.s2u2m.uam.cli.CliRunner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 部署期命令的分发：第一个参数是命令名，其余原样交给命令；退出码由命令决定，用法错误为 2。 */
class CliRunnerTest {

  private final List<List<String>> received = new ArrayList<>();
  private final CliCommand echo =
      new CliCommand() {
        @Override
        public String name() {
          return "echo";
        }

        @Override
        public int run(List<String> args, InputStream in, PrintStream out, PrintStream err) {
          received.add(args);
          return 7;
        }
      };
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private int dispatch(String... args) {
    return new CliRunner(List.of(echo))
        .dispatch(
            List.of(args),
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @Test
  void dispatchesToTheNamedCommandWithTheRemainingArguments() {
    int code = dispatch("echo", "--a", "b c", "--d=e");

    assertThat(code).isEqualTo(7);
    assertThat(received).containsExactly(List.of("--a", "b c", "--d=e"));
  }

  @Test
  void noCommandPrintsUsageAndReturnsTwo() {
    assertThat(dispatch()).isEqualTo(2);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("用法").contains("echo");
  }

  @Test
  void unknownCommandPrintsUsageAndReturnsTwo() {
    assertThat(dispatch("bogus")).isEqualTo(2);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("bogus");
    assertThat(received).isEmpty();
  }

  @Test
  void exitCodeIsExposedForTheLauncher() {
    CliRunner runner = new CliRunner(List.of(echo));
    assertThat(runner.getExitCode()).isZero();

    runner.dispatch(
        List.of("echo"),
        new ByteArrayInputStream(new byte[0]),
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(new ByteArrayOutputStream()));

    assertThat(runner.getExitCode()).isEqualTo(7);
  }
}
