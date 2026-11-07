package tech.toolkit.tenantcore.uam;

import java.util.UUID;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Argon2id password hashing (m=19 MiB, t=2, p=1). Plain passwords are never stored. */
public class PasswordHasher {

  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

  public String hash(String password) {
    return encoder.encode(password);
  }

  public boolean matches(String password, String encodedHash) {
    return encoder.matches(password, encodedHash);
  }

  /**
   * Does the same work as {@link #matches} against a throw-away hash, so unknown accounts cost
   * about as much time as wrong passwords.
   */
  public boolean dummyMatches(String password) {
    return encoder.matches(password, dummyHash);
  }

  @Override
  public String toString() {
    return "PasswordHasher[argon2id]";
  }
}
