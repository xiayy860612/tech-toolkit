package tech.toolkit.tenantcore.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

  // 测试里用最小参数加速；生产默认值由配置给出
  private final PasswordHasher hasher =
      new PasswordHasher(new AdminCenterProperties.Password(1024, 1, 1));

  @Test
  void hashesAreVerifiableAndDoNotContainThePlaintext() {
    String hash = hasher.hash("Str0ng-Passw0rd");
    assertThat(hash).startsWith("$argon2id$").doesNotContain("Str0ng-Passw0rd");
    assertThat(hasher.matches("Str0ng-Passw0rd", hash)).isTrue();
    assertThat(hasher.matches("wrong", hash)).isFalse();
  }

  @Test
  void sameInputProducesDifferentHashes() {
    assertThat(hasher.hash("Str0ng-Passw0rd")).isNotEqualTo(hasher.hash("Str0ng-Passw0rd"));
  }

  @Test
  void dummyMatchAlwaysFailsButDoesTheWork() {
    assertThat(hasher.dummyMatch("anything")).isFalse();
  }

  @Test
  void generatedTemporaryPasswordsAre16CharsAndSatisfyThePolicy() {
    TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();
    PasswordPolicy policy = new PasswordPolicy();
    for (int i = 0; i < 50; i++) {
      String pw = generator.generate();
      assertThat(pw).hasSize(16);
      assertThat(policy.violations(pw, "someone", java.util.Set.of())).isEmpty();
    }
  }
}
