package com.s2u2m.uam.domain.port;

import java.util.OptionalLong;

/** 登录失败限制的存储；{@code subject} 见 {@code LoginThrottlePolicy.subject}。 */
public interface LoginThrottle {

  /** 该来源被拒绝时返回剩余秒数（即 {@code Retry-After}），否则为空。 */
  OptionalLong blockedFor(String subject);

  void recordFailure(String subject);

  /** 登录成功后清零。 */
  void reset(String subject);
}
