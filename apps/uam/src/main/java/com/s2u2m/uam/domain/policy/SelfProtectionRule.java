package com.s2u2m.uam.domain.policy;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.User;
import java.util.UUID;

/**
 * 写操作的自我保护：停用（FR-025）——不能停用操作人自己所属的用户，不能停用最后一个 {@code ACTIVE} 用户；强制下线（FR-030）——不能终止操作人自己正在使用的会话。
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

  /**
   * 校验能否强制下线 {@code target}（FR-030）：目标是操作人当前使用的会话时抛 {@link DomainException}；须先于任何终止动作调用。
   *
   * @param operatorSessionRef 操作人当前会话的引用，缺省为 {@code null}（不构成「自己」）
   */
  public static void checkTerminate(SessionRef target, UUID operatorSessionRef) {
    if (target.value().equals(operatorSessionRef)) {
      throw new DomainException(ErrorCode.CANNOT_TERMINATE_OWN_SESSION, "不能强制下线自己正在使用的会话");
    }
  }
}
