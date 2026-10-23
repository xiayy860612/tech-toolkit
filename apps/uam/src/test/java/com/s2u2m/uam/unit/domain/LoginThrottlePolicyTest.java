package com.s2u2m.uam.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.support.TestSecrets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** T045：同一「账号 + 来源地址」连续失败 5 次后拒绝该来源 15 分钟，不锁账号。 */
class LoginThrottlePolicyTest {

  private final LoginThrottlePolicy policy = new LoginThrottlePolicy(5, Duration.ofMinutes(15));

  @Test
  void blocksOnceFailuresReachTheThreshold() {
    assertThat(policy.isBlocked(0)).isFalse();
    assertThat(policy.isBlocked(4)).isFalse();
    assertThat(policy.isBlocked(5)).isTrue();
    assertThat(policy.isBlocked(6)).isTrue();
  }

  @Test
  void exposesTheConfiguredLimits() {
    assertThat(policy.maxFailures()).isEqualTo(5);
    assertThat(policy.block()).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void subjectIsSha256OfNormalizedLoginNameAndSource() {
    String subject = policy.subject(LoginName.of("Admin"), "203.0.113.7");

    assertThat(subject).isEqualTo(TestSecrets.sha256Hex("admin|203.0.113.7"));
  }

  @Test
  void subjectIgnoresAccountNameCase() {
    assertThat(policy.subject(LoginName.of("ADMIN"), "203.0.113.7"))
        .isEqualTo(policy.subject(LoginName.of("admin"), "203.0.113.7"));
  }

  @Test
  void differentSourcesOrAccountsHaveDifferentSubjects() {
    String base = policy.subject(LoginName.of("admin"), "203.0.113.7");

    assertThat(policy.subject(LoginName.of("admin"), "203.0.113.8")).isNotEqualTo(base);
    assertThat(policy.subject(LoginName.of("other"), "203.0.113.7")).isNotEqualTo(base);
  }

  @Test
  void sourceIsTrimmedAndLowerCased() {
    assertThat(policy.subject(LoginName.of("admin"), " 2001:DB8::1 "))
        .isEqualTo(policy.subject(LoginName.of("admin"), "2001:db8::1"));
  }
}
