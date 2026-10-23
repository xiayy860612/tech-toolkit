package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.model.Session;

/** 登录结果：机密的会话标识（只给 Admin Center 写 cookie）、cookie 的 {@code Max-Age}（剩余绝对寿命，秒）与会话数据。 */
public record LoginResult(String sessionId, long maxAgeSeconds, Session session) {

  /** 不输出会话标识，避免误入日志。 */
  @Override
  public String toString() {
    return "LoginResult[maxAgeSeconds=" + maxAgeSeconds + ", session=" + session + "]";
  }
}
