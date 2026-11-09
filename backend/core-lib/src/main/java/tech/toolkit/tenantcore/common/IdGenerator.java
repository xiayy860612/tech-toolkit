package tech.toolkit.tenantcore.common;

import java.security.SecureRandom;
import java.util.Base64;

/** Generates the identifiers and secrets that are not database keys. */
public class IdGenerator {

  private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

  private final SecureRandom random = new SecureRandom();

  /** Public User ID, e.g. {@code tu_8f3a1c0d9e2b}. */
  public String tenantUserId() {
    return "tu_" + randomString(12);
  }

  /** Non-secret Session identifier written to Internal JWTs, e.g. {@code s_77e0...}. */
  public String sid() {
    return "s_" + randomString(16);
  }

  /** High-entropy Opaque Token value; only its hash is stored. The prefix helps leak scanning. */
  public String opaqueTokenValue() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return "tk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String randomString(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }
}
