package tech.toolkit.tenantcore.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;

class PasswordReaderTest {

  /** Scripted terminal: returns the queued answers and remembers the prompts shown. */
  private static final class FakeTerminal implements PasswordReader.Terminal {
    final Deque<String> answers = new ArrayDeque<>();
    final java.util.List<String> prompts = new java.util.ArrayList<>();

    FakeTerminal(String... answers) {
      this.answers.addAll(List.of(answers));
    }

    @Override
    public char[] readPassword(String prompt) {
      prompts.add(prompt);
      return answers.poll().toCharArray();
    }
  }

  private static ByteArrayInputStream stdin(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void asksTwiceOnTheTerminalAndReturnsTheMatchingPassword() {
    FakeTerminal terminal = new FakeTerminal("correct horse battery", "correct horse battery");
    String password = new PasswordReader(terminal, stdin("")).read(false);
    assertThat(password).isEqualTo("correct horse battery");
    assertThat(terminal.prompts).hasSize(2);
  }

  @Test
  void rejectsTwoDifferentEntries() {
    FakeTerminal terminal = new FakeTerminal("correct horse battery", "something else entirely");
    assertThatThrownBy(() -> new PasswordReader(terminal, stdin("")).read(false))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION))
        .hasMessageContaining("do not match");
  }

  @Test
  void readsOneLineFromStandardInput() {
    String password =
        new PasswordReader(null, stdin("correct horse battery\nsecond line\n")).read(true);
    assertThat(password).isEqualTo("correct horse battery");
  }

  @Test
  void rejectsEmptyStandardInput() {
    assertThatThrownBy(() -> new PasswordReader(null, stdin("")).read(true))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("empty");
    assertThatThrownBy(() -> new PasswordReader(null, stdin("\n")).read(true))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void aBrokenStandardInputIsReportedAsAnIoError() {
    java.io.InputStream broken =
        new java.io.InputStream() {
          @Override
          public int read() throws java.io.IOException {
            throw new java.io.IOException("boom");
          }
        };
    assertThatThrownBy(() -> new PasswordReader(null, broken).read(true))
        .isInstanceOf(java.io.UncheckedIOException.class);
  }

  @Test
  void aTerminalThatReturnsNothingIsRejected() {
    PasswordReader.Terminal silent = prompt -> null;
    assertThatThrownBy(() -> new PasswordReader(silent, stdin("")).read(false))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("do not match");
  }

  @Test
  void theSystemTerminalIsOnlyAvailableWithAnInteractiveConsole() {
    // under Maven there is no console, so the reader falls back to --password-stdin
    assertThat(PasswordReader.systemTerminal() == null).isEqualTo(System.console() == null);
  }

  @Test
  void withoutATerminalItAsksForPasswordStdin() {
    assertThatThrownBy(() -> new PasswordReader(null, stdin("")).read(false))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("--password-stdin");
  }
}
