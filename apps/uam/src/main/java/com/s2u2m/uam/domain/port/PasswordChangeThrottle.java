package com.s2u2m.uam.domain.port;

import java.util.OptionalLong;

/**
 * 改密时「当前密码连续错误」限制的存储（口径同 {@link LoginThrottle}）；{@code subject} 见 {@code
 * LoginThrottlePolicy.accountSubject}。方法名与登录限流区分，以便同一个 Redis 实现同时承担两者。
 */
public interface PasswordChangeThrottle {

  /** 该来源被拒绝时返回剩余秒数（即 {@code Retry-After}），否则为空。 */
  OptionalLong changeBlockedFor(String subject);

  void recordChangeFailure(String subject);

  /** 当前密码校验通过后清零。 */
  void resetChange(String subject);
}
