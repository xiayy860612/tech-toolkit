package tech.toolkit.tenantcore.uam;

import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;

/** Password rules: 12-128 characters, not blank, not equal to the username (case-insensitive). */
public class PasswordPolicy {

  public static final int MIN_LENGTH = 12;
  public static final int MAX_LENGTH = 128;

  public void validate(String password, String username) {
    if (password == null
        || password.isBlank()
        || password.length() < MIN_LENGTH
        || password.length() > MAX_LENGTH) {
      throw new DomainException(ErrorCode.PASSWORD_POLICY, rules());
    }
    if (username != null && password.equalsIgnoreCase(username)) {
      throw new DomainException(ErrorCode.PASSWORD_POLICY, rules());
    }
  }

  private static String rules() {
    return "Password must be "
        + MIN_LENGTH
        + "-"
        + MAX_LENGTH
        + " characters long, not blank, and must not equal the username";
  }
}
