package com.s2u2m.uam.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.policy.AccountNameRules;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T045：账号名 3–32 个字母、数字或 {@code _ . -}，大小写不敏感。 */
class AccountNameRulesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "abc",
        "Admin",
        "a_b.c-d",
        "user.name-01",
        "A1_",
        "abcdefghijklmnopqrstuvwxyz012345"
      })
  void acceptsValidNames(String name) {
    assertThat(AccountNameRules.validate(name)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void blankIsRequired(String name) {
    assertThat(AccountNameRules.validate(name))
        .containsExactly(new FieldError("accountName", "required"));
  }

  @Test
  void nullIsRequired() {
    assertThat(AccountNameRules.validate(null))
        .containsExactly(new FieldError("accountName", "required"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "ab"})
  void shorterThanThreeIsTooShort(String name) {
    assertThat(AccountNameRules.validate(name))
        .containsExactly(new FieldError("accountName", "too_short"));
  }

  @Test
  void longerThanThirtyTwoIsTooLong() {
    assertThat(AccountNameRules.validate("x".repeat(33)))
        .containsExactly(new FieldError("accountName", "too_long"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "has space",
        "tab\tname",
        "名字名字",
        "a@b.c",
        "semi;colon",
        "slash/name",
        " lead",
        "trail "
      })
  void otherCharactersAreInvalid(String name) {
    List<FieldError> errors = AccountNameRules.validate(name);

    assertThat(errors).containsExactly(new FieldError("accountName", "invalid_chars"));
  }

  @Test
  void normalizationIsCaseInsensitive() {
    LoginName name = LoginName.of("Admin.User-1");

    assertThat(name.raw()).isEqualTo("Admin.User-1");
    assertThat(name.normalized()).isEqualTo("admin.user-1");
    assertThat(LoginName.of("ADMIN.USER-1").normalized()).isEqualTo(name.normalized());
  }
}
