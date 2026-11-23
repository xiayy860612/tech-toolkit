package tech.toolkit.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** FR-013: an access token must always live shorter than the refresh token. */
class AuthPropertiesTest {

  private static AuthProperties.Tokens tokens(Duration access, Duration refresh) {
    return new AuthProperties.Tokens(access, Duration.ofSeconds(60), refresh);
  }

  @Test
  void theDefaultsAreValid() {
    assertThatCode(() -> tokens(Duration.ofMinutes(10), Duration.ofHours(8)))
        .doesNotThrowAnyException();
  }

  @Test
  void anAccessTokenLongerThanTheRefreshTokenIsRefusedAtStartup() {
    assertThatThrownBy(() -> tokens(Duration.ofHours(2), Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("access");
  }

  @Test
  void equalLifetimesAreRefusedToo() {
    assertThatThrownBy(() -> tokens(Duration.ofHours(1), Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theSameRuleIsAvailableForPerClientValues() {
    assertThat(AuthProperties.Tokens.lifetimesAreValid(Duration.ofMinutes(2), Duration.ofHours(1)))
        .isTrue();
    assertThat(AuthProperties.Tokens.lifetimesAreValid(Duration.ofHours(2), Duration.ofHours(1)))
        .isFalse();
    assertThat(AuthProperties.Tokens.lifetimesAreValid(Duration.ofHours(1), Duration.ofHours(1)))
        .isFalse();
  }
}
