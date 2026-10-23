package com.s2u2m.uam.domain.policy;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.User;
import java.util.UUID;

/**
 * 停用的自我保护（FR-025）：不能停用操作人自己所属的用户，不能停用最后一个 {@code ACTIVE} 用户。
 *
 * <p>自我保护优先于「最后一个」；对已停用的目标重复停用不受「最后一个」限制（它本来就不是正常用户），但仍受自我保护——操作人不会停用自己。
 */
public final class SelfProtectionRule {

  private SelfProtectionRule() {}

  /**
   * 校验能否停用 {@code target}；不允许则抛 {@link DomainException}。
   *
   * @param operatorUserId 操作人所属用户，缺省为 {@code null}（不构成「自己」）
   * @param activeUsers 当前 {@code ACTIVE} 用户总数（须在取得状态变更锁之后读取）
   */
  public static void checkDisable(User target, UUID operatorUserId, long activeUsers) {
    if (target.id().equals(operatorUserId)) {
      throw new DomainException(ErrorCode.CANNOT_DISABLE_SELF, "不能停用自己所属的用户");
    }
    if (target.isActive() && activeUsers <= 1) {
      throw new DomainException(ErrorCode.CANNOT_DISABLE_LAST_ACTIVE_USER, "不能停用最后一个正常用户");
    }
  }
}
