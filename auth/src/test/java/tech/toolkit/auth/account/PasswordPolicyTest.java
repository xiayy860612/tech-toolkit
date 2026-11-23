package tech.toolkit.auth.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.toolkit.auth.account.PasswordPolicy.Violation;

/** FR-017: at least 12 characters, not a common password, not the username. */
class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy();

  @Test
  void acceptsALongUncommonPassword() {
    assertThat(policy.check("Correct-Horse-Battery-9", "alice")).isEmpty();
  }

  @Test
  void twelveCharactersIsEnough() {
    assertThat(policy.check("abcDEF123!@#", "alice")).isEmpty();
  }

  @Test
  void rejectsAPasswordShorterThanTwelve() {
    assertThat(policy.check("Sh0rt-pw!x", "alice")).containsExactly(Violation.TOO_SHORT);
  }

  @Test
  void rejectsACommonPasswordWhateverItsCase() {
    assertThat(policy.check("PasswordPassword", "alice")).containsExactly(Violation.TOO_COMMON);
    assertThat(policy.check("123456789012", "alice")).contains(Violation.TOO_COMMON);
  }

  @Test
  void rejectsThePasswordThatIsTheUsername() {
    assertThat(policy.check("Alice.Liddell.01", "alice.liddell.01"))
        .containsExactly(Violation.SAME_AS_USERNAME);
  }

  @Test
  void reportsEveryBrokenRuleAtOnce() {
    assertThat(policy.check("alice", "alice"))
        .containsExactlyInAnyOrder(Violation.TOO_SHORT, Violation.SAME_AS_USERNAME);
  }

  @Test
  void aMissingPasswordIsTooShort() {
    assertThat(policy.check(null, "alice")).contains(Violation.TOO_SHORT);
  }

  @Test
  void everyViolationHasAMessageForTheUser() {
    for (Violation violation : Violation.values()) {
      assertThat(violation.message()).isNotBlank();
    }
  }
}
