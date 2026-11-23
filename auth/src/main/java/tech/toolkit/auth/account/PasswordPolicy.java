package tech.toolkit.auth.account;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The password rules of FR-017, following NIST SP 800-63B: length matters, composition rules do
 * not. A password needs at least 12 characters, must not be a well-known one, and must not be the
 * username. Every broken rule is reported at once.
 */
@Component
public class PasswordPolicy {

  static final int MIN_LENGTH = 12;

  /** The rules a password can break, each with the text shown to the user. */
  public enum Violation {
    TOO_SHORT("密码至少 12 位"),
    TOO_COMMON("密码过于常见，请换一个"),
    SAME_AS_USERNAME("密码不能与用户名相同");

    private final String message;

    Violation(String message) {
      this.message = message;
    }

    public String message() {
      return message;
    }
  }

  private final Set<String> common;

  public PasswordPolicy() {
    this.common = loadCommonPasswords();
  }

  /** The rules the password breaks; empty when it is acceptable. */
  public List<Violation> check(String password, String username) {
    List<Violation> violations = new ArrayList<>();
    if (password == null || password.length() < MIN_LENGTH) {
      violations.add(Violation.TOO_SHORT);
    }
    if (password != null && common.contains(password.toLowerCase(Locale.ROOT))) {
      violations.add(Violation.TOO_COMMON);
    }
    if (password != null && username != null && password.equalsIgnoreCase(username.trim())) {
      violations.add(Violation.SAME_AS_USERNAME);
    }
    return violations;
  }

  private static Set<String> loadCommonPasswords() {
    try (InputStream in = PasswordPolicy.class.getResourceAsStream("/common-passwords.txt")) {
      if (in == null) {
        throw new IllegalStateException("common-passwords.txt is missing from the classpath");
      }
      Set<String> passwords = new HashSet<>();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        reader
            .lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .forEach(line -> passwords.add(line.toLowerCase(Locale.ROOT)));
      }
      return Set.copyOf(passwords);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
