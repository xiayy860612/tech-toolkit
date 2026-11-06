package tech.toolkit.tenantcore.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;

/**
 * Reads the initial password without ever taking it from a command-line argument: hidden prompt
 * with confirmation on a terminal, or one line from standard input with {@code --password-stdin}.
 */
public class PasswordReader {

  /** Hidden-input terminal; {@code null} when there is no interactive console. */
  public interface Terminal {
    char[] readPassword(String prompt);
  }

  private final Terminal terminal;
  private final InputStream stdin;

  public PasswordReader(Terminal terminal, InputStream stdin) {
    this.terminal = terminal;
    this.stdin = stdin;
  }

  public static Terminal systemTerminal() {
    Console console = System.console();
    if (console == null) {
      return null;
    }
    return prompt -> console.readPassword("%s", prompt);
  }

  public String read(boolean fromStdin) {
    if (fromStdin) {
      return requireNotEmpty(readLine(), "Password from standard input is empty");
    }
    if (terminal == null) {
      throw new DomainException(
          ErrorCode.VALIDATION,
          "No terminal available; pipe the password and pass --password-stdin");
    }
    char[] first = terminal.readPassword("Password: ");
    char[] second = terminal.readPassword("Confirm password: ");
    try {
      if (first == null || second == null || !Arrays.equals(first, second)) {
        throw new DomainException(ErrorCode.VALIDATION, "Passwords do not match");
      }
      return requireNotEmpty(new String(first), "Password is empty");
    } finally {
      Arrays.fill(first == null ? new char[0] : first, ' ');
      Arrays.fill(second == null ? new char[0] : second, ' ');
    }
  }

  private String readLine() {
    try {
      return new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8)).readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String requireNotEmpty(String value, String message) {
    if (value == null || value.isEmpty()) {
      throw new DomainException(ErrorCode.VALIDATION, message);
    }
    return value;
  }
}
