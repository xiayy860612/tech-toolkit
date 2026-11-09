package tech.toolkit.tenantcore.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdGeneratorTest {

  private final IdGenerator ids = new IdGenerator();

  @Test
  void tenantUserIdHasPrefixAndShape() {
    assertThat(ids.tenantUserId()).matches("^tu_[a-z0-9]{12}$");
  }

  @Test
  void sidHasPrefixAndShape() {
    assertThat(ids.sid()).matches("^s_[a-z0-9]{16}$");
  }

  @Test
  void opaqueTokenValueHasPrefixAndEntropy() {
    assertThat(ids.opaqueTokenValue()).matches("^tk_[A-Za-z0-9_-]{43}$");
  }

  @Test
  void generatedValuesDoNotRepeat() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 2000; i++) {
      seen.add(ids.tenantUserId());
      seen.add(ids.sid());
      seen.add(ids.opaqueTokenValue());
    }
    assertThat(seen).hasSize(6000);
  }
}
