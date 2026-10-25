package com.s2u2m.uam.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/accounts/{accountId}/password} 的请求体（契约 {@code
 * ChangePasswordRequest}）。新密码的规则（含必填）由用例按密码强度策略校验并给出 422 {@code weak_password}；{@code clientIp}
 * 由被授予断言权限的客户端提供。
 */
public record ChangePasswordRequest(
    @NotEmpty @Size(max = 256) String currentPassword,
    String newPassword,
    @NotBlank @Size(max = 64) String clientIp) {

  /** 不输出密码，避免误入日志。 */
  @Override
  public String toString() {
    return "ChangePasswordRequest[clientIp=" + clientIp + "]";
  }
}
