package com.s2u2m.uam.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** {@code POST /v1/auth/login} 的请求体（契约 {@code LoginRequest}）。{@code clientIp} 由被授予断言权限的客户端提供。 */
public record LoginRequest(
    @NotBlank @Size(max = 64) String accountName,
    @NotEmpty @Size(max = 256) String password,
    @NotBlank @Size(max = 64) String clientIp) {

  /** 不输出密码，避免误入日志。 */
  @Override
  public String toString() {
    return "LoginRequest[accountName=" + accountName + ", clientIp=" + clientIp + "]";
  }
}
