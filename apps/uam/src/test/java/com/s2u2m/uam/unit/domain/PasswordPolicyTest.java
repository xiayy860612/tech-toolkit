package com.s2u2m.uam.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.policy.PasswordPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T045：密码默认至少 10 位且同时含字母与数字（可配置）。 */
class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy(10);

  @ParameterizedTest
  @ValueSource(strings = {"abcdefghi1", "1234567890a", "Passw0rd-fake", "密码密码密码密码a1"})
  void acceptsLongEnoughPasswordsWithLetterAndDigit(String password) {
    assertThat(policy.validate("password", password)).isEmpty();
  }

  @Test
  void nullOrEmptyIsRequired() {
    assertThat(policy.validate("password", null))
        .containsExactly(new FieldError("password", "required"));
    assertThat(policy.validate("password", ""))
        .containsExactly(new FieldError("password", "required"));
  }

  @Test
  void shorterThanMinimumIsTooShort() {
    assertThat(policy.validate("password", "abcd1234"))
        .containsExactly(new FieldError("password", "too_short"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"abcdefghijkl", "1234567890123", "!!!!!!!!!!!!"})
  void missingLetterOrDigitIsTooWeak(String password) {
    assertThat(policy.validate("password", password))
        .containsExactly(new FieldError("password", "too_weak"));
  }

  @Test
  void tooShortIsReportedBeforeTooWeak() {
    assertThat(policy.validate("password", "abc"))
        .containsExactly(new FieldError("password", "too_short"));
  }

  @Test
  void passwordsLongerThan128CodePointsAreRejected() {
    assertThat(policy.validate("password", "a1".repeat(65)))
        .containsExactly(new FieldError("password", "too_long"));
    assertThat(policy.validate("password", "a1".repeat(64))).isEmpty();
  }

  @Test
  void everyAcceptedPasswordFitsTheLoginRequestLimitOf256Utf16Chars() {
    // 登录请求按 UTF-16 字符数限 256；策略按码点数限制，最坏情形是每个码点占 2 个字符（如 emoji）
    String widest = "\uD83D\uDE00".repeat(PasswordPolicy.MAX_LENGTH - 2) + "a1";

    assertThat(policy.validate("password", widest)).isEmpty();
    assertThat(widest.length()).isLessThanOrEqualTo(256);
    assertThat(policy.validate("password", widest + "\uD83D\uDE00"))
        .containsExactly(new FieldError("password", "too_long"));
  }

  @Test
  void minimumLengthIsConfigurableAndFieldNameIsPassedThrough() {
    PasswordPolicy lenient = new PasswordPolicy(6);

    assertThat(lenient.validate("newPassword", "abc123")).isEmpty();
    assertThat(lenient.validate("newPassword", "abc12"))
        .containsExactly(new FieldError("newPassword", "too_short"));
    assertThat(lenient.minLength()).isEqualTo(6);
  }
}
