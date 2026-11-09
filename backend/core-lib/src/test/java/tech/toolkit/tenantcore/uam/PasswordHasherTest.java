package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

  private final PasswordHasher hasher = new PasswordHasher();

  @Test
  void producesArgon2idEncodedHashWithoutThePlaintext() {
    String hash = hasher.hash("correct horse battery");
    assertThat(hash).startsWith("$argon2id$").doesNotContain("correct horse battery");
  }

  @Test
  void usesAFreshSaltEveryTime() {
    assertThat(hasher.hash("correct horse battery"))
        .isNotEqualTo(hasher.hash("correct horse battery"));
  }

  @Test
  void matchesOnlyTheRightPassword() {
    String hash = hasher.hash("correct horse battery");
    assertThat(hasher.matches("correct horse battery", hash)).isTrue();
    assertThat(hasher.matches("wrong horse battery", hash)).isFalse();
  }

  @Test
  void dummyVerificationAlwaysFailsButDoesTheWork() {
    assertThat(hasher.dummyMatches("anything at all")).isFalse();
  }
}
