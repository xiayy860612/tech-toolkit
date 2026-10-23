package com.s2u2m.uam.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.policy.SelfProtectionRule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T108：停用的自我保护——不能停用操作人自己所属的用户，不能停用最后一个 {@code ACTIVE} 用户。 */
class SelfProtectionRuleTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");

  private static User user(UserStatus status) {
    return new User(UUID.randomUUID(), "张三", status, T0);
  }

  private static void assertRejected(
      User target, UUID operatorUserId, long active, ErrorCode code) {
    assertThatThrownBy(() -> SelfProtectionRule.checkDisable(target, operatorUserId, active))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  @Test
  void anOperatorCannotDisableTheirOwnUser() {
    User target = user(UserStatus.ACTIVE);

    assertRejected(target, target.id(), 5, ErrorCode.CANNOT_DISABLE_SELF);
  }

  @Test
  void selfProtectionWinsOverTheLastActiveUserRule() {
    User target = user(UserStatus.ACTIVE);

    assertRejected(target, target.id(), 1, ErrorCode.CANNOT_DISABLE_SELF);
  }

  @Test
  void selfProtectionAlsoAppliesToAnAlreadyDisabledOwnUser() {
    User target = user(UserStatus.DISABLED);

    assertRejected(target, target.id(), 3, ErrorCode.CANNOT_DISABLE_SELF);
  }

  @Test
  void theLastActiveUserCannotBeDisabled() {
    assertRejected(
        user(UserStatus.ACTIVE), UUID.randomUUID(), 1, ErrorCode.CANNOT_DISABLE_LAST_ACTIVE_USER);
  }

  @Test
  void anActiveTargetWithZeroCountedActiveUsersIsStillProtected() {
    assertRejected(
        user(UserStatus.ACTIVE), UUID.randomUUID(), 0, ErrorCode.CANNOT_DISABLE_LAST_ACTIVE_USER);
  }

  @Test
  void anActiveUserCanBeDisabledWhenAnotherActiveUserRemains() {
    assertThatCode(
            () -> SelfProtectionRule.checkDisable(user(UserStatus.ACTIVE), UUID.randomUUID(), 2))
        .doesNotThrowAnyException();
  }

  @Test
  void anAlreadyDisabledUserPassesEvenWhenOnlyOneOtherUserIsActive() {
    assertThatCode(
            () -> SelfProtectionRule.checkDisable(user(UserStatus.DISABLED), UUID.randomUUID(), 1))
        .doesNotThrowAnyException();
  }

  @Test
  void aMissingOperatorUserIsNotSelf() {
    assertThatCode(() -> SelfProtectionRule.checkDisable(user(UserStatus.ACTIVE), null, 2))
        .doesNotThrowAnyException();
  }
}
