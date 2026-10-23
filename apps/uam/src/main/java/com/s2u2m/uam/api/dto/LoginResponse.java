package com.s2u2m.uam.api.dto;

import com.s2u2m.uam.api.dto.VerifyResponse.SessionAccount;
import com.s2u2m.uam.api.dto.VerifyResponse.SessionUser;
import com.s2u2m.uam.application.LoginResult;
import java.time.Instant;
import java.util.UUID;

/**
 * 登录成功的响应（契约 {@code LoginResponse}）。{@code sessionId} 是机密，只给 Admin Center 写 cookie；{@code
 * maxAgeSeconds} 是剩余绝对寿命， 用作 cookie 的 {@code Max-Age}。
 */
public record LoginResponse(
    String sessionId,
    long maxAgeSeconds,
    SessionUser user,
    SessionAccount account,
    UUID sessionRef,
    Instant expiresAt) {

  public static LoginResponse of(LoginResult result) {
    VerifyResponse data = VerifyResponse.of(result.session());
    return new LoginResponse(
        result.sessionId(),
        result.maxAgeSeconds(),
        data.user(),
        data.account(),
        data.sessionRef(),
        data.expiresAt());
  }

  /** 不输出会话标识，避免误入日志。 */
  @Override
  public String toString() {
    return "LoginResponse[sessionRef=" + sessionRef + "]";
  }
}
