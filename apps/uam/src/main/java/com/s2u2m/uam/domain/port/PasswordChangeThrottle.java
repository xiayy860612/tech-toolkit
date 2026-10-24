package com.s2u2m.uam.domain.port;

import java.util.OptionalLong;

/**
 * 改密时「当前密码连续错误」限制的存储（口径同 {@link LoginThrottle}）；{@code subject} 见 {@code
 * LoginThrottlePolicy.accountSubject}。方法名与登录限流区分，以便同一个 Redis 实现同时承担两者。
 */
public interface PasswordChangeThrottle {

  /** 在哈希比对之前原子占用一次尝试名额（口径同 {@link LoginThrottle#admit}）：被拒绝返回剩余秒数，放行返回空。 */
  OptionalLong admitChange(String subject);

  /** 当前密码校验通过后清零。 */
  void resetChange(String subject);
}
