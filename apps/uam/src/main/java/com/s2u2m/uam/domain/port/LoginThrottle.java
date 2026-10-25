package com.s2u2m.uam.domain.port;

import java.util.OptionalLong;

/** 登录失败限制的存储；{@code subject} 见 {@code LoginThrottlePolicy.subject}。 */
public interface LoginThrottle {

  /**
   * 在哈希比对<b>之前</b>原子占用该来源的一次尝试名额：超过上限（默认 5 次）的尝试被拒绝，返回剩余秒数（即 {@code Retry-After}）；放行返回空。
   * 占用与判定是同一个原子操作，并发的错误尝试不会同时通过闸门；成功后由 {@link #reset} 清零。
   */
  OptionalLong admit(String subject);

  /** 登录成功后清零。 */
  void reset(String subject);
}
