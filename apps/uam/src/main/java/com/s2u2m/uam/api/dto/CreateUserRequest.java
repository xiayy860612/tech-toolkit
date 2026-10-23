package com.s2u2m.uam.api.dto;

/**
 * {@code POST /v1/users} 的请求体（契约 {@code CreateUserRequest}）。字段规则（含「必填」）由 {@code
 * AccountProvisioningService} 统一校验并给出 422 字段级错误，这里不加 Bean 校验。
 */
public record CreateUserRequest(String userName, String accountName, String password) {

  /** 不输出密码，避免误入日志。 */
  @Override
  public String toString() {
    return "CreateUserRequest[userName=" + userName + ", accountName=" + accountName + "]";
  }
}
