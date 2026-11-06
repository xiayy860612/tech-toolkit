package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy();

  @Test
  void acceptsTheLengthBoundaries() {
    assertThatCode(() -> policy.validate("a".repeat(12), "admin")).doesNotThrowAnyException();
    assertThatCode(() -> policy.validate("a".repeat(128), "admin")).doesNotThrowAnyException();
  }

  @Test
  void rejectsTooShortAndTooLong() {
    assertThatThrownBy(() -> policy.validate("a".repeat(11), "admin"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PASSWORD_POLICY));
    assertThatThrownBy(() -> policy.validate("a".repeat(129), "admin"))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void rejectsNullOrBlank() {
    assertThatThrownBy(() -> policy.validate(null, "admin")).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> policy.validate(" ".repeat(20), "admin"))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void rejectsThePasswordEqualToTheUsernameIgnoringCase() {
    assertThatThrownBy(() -> policy.validate("Administrator1", "administrator1"))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PASSWORD_POLICY));
  }

  @Test
  void messageListsTheRules() {
    assertThatThrownBy(() -> policy.validate("short", "admin"))
        .hasMessageContaining("12")
        .hasMessageContaining("128");
  }
}
