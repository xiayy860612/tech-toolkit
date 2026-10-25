package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.infrastructure.security.Argon2PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** T061：Argon2id（DelegatingPasswordEncoder 格式）、参数落后可升级、伪哈希与真哈希同参数。 */
class Argon2PasswordHasherTest {

  private final Argon2PasswordHasher hasher = new Argon2PasswordHasher();

  @Test
  void hashesWithArgon2idInDelegatingFormatAndVerifies() {
    String hash = hasher.hash("Passw0rd-fake-1");

    assertThat(hash).startsWith("{argon2@SpringSecurity_v5_8}$argon2id$");
    assertThat(hash).doesNotContain("Passw0rd-fake-1");
    assertThat(hasher.matches("Passw0rd-fake-1", hash)).isTrue();
    assertThat(hasher.matches("Passw0rd-fake-2", hash)).isFalse();
  }

  @Test
  void eachHashUsesAFreshSalt() {
    assertThat(hasher.hash("Passw0rd-fake-1")).isNotEqualTo(hasher.hash("Passw0rd-fake-1"));
  }

  @Test
  void currentParametersDoNotNeedAnUpgrade() {
    assertThat(hasher.needsUpgrade(hasher.hash("Passw0rd-fake-1"))).isFalse();
  }

  @Test
  void weakerParametersStillVerifyAndAreFlaggedForUpgrade() {
    String weaker =
        "{argon2@SpringSecurity_v5_8}"
            + new Argon2PasswordEncoder(16, 32, 1, 1024, 1).encode("Passw0rd-fake-1");

    assertThat(hasher.matches("Passw0rd-fake-1", weaker)).isTrue();
    assertThat(hasher.needsUpgrade(weaker)).isTrue();
  }

  @Test
  void anotherAlgorithmIdIsFlaggedForUpgrade() {
    String legacy =
        "{argon2}"
            + Argon2PasswordEncoder.defaultsForSpringSecurity_v5_2().encode("Passw0rd-fake-1");

    assertThat(hasher.matches("Passw0rd-fake-1", legacy)).isTrue();
    assertThat(hasher.needsUpgrade(legacy)).isTrue();
  }

  @Test
  void dummyHashIsARealHashThatNeverMatchesWithTheSameCostAsRealOnes() {
    String dummy = hasher.dummyHash();

    assertThat(dummy).startsWith("{argon2@SpringSecurity_v5_8}$argon2id$");
    assertThat(hasher.needsUpgrade(dummy)).isFalse();
    assertThat(hasher.matches("Passw0rd-fake-1", dummy)).isFalse();
    assertThat(hasher.dummyHash()).isEqualTo(dummy);
  }
}
