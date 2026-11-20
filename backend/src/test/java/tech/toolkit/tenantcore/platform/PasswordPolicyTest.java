package tech.toolkit.tenantcore.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy();

  @Test
  void acceptsStrongPassword() {
    assertThat(policy.violations("Str0ng-Passw0rd", "alice", Set.of())).isEmpty();
  }

  @Test
  void rejectsShortPassword() {
    assertThat(policy.violations("Ab1!xyz", "alice", Set.of())).isNotEmpty();
  }

  @Test
  void requiresAtLeastThreeCharacterClasses() {
    assertThat(policy.violations("alllowercaseletters", "alice", Set.of())).isNotEmpty();
    assertThat(policy.violations("lowerAndUPPERonly", "alice", Set.of())).isNotEmpty();
    assertThat(policy.violations("lower-and-1234-sym", "alice", Set.of())).isEmpty();
  }

  @Test
  void rejectsPasswordContainingUsernameIgnoringCase() {
    assertThat(policy.violations("My-ALICE-Passw0rd", "alice", Set.of())).isNotEmpty();
  }

  @Test
  void rejectsPasswordEqualToForbiddenValues() {
    assertThat(policy.violations("Str0ng-Passw0rd", "bob", Set.of("Str0ng-Passw0rd"))).isNotEmpty();
  }
}
