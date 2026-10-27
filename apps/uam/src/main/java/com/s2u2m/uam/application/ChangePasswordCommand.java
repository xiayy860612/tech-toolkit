package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.model.OperatorContext;
import java.util.UUID;

/** 改密输入：{@code clientIp} 由调用方提供（同登录）；操作人用于授权校验与写操作日志。 */
public record ChangePasswordCommand(
    OperatorContext operator,
    UUID accountId,
    String clientIp,
    String currentPassword,
    String newPassword) {

  /** 不输出密码，避免误入日志。 */
  @Override
  public String toString() {
    return "ChangePasswordCommand[accountId=" + accountId + ", clientIp=" + clientIp + "]";
  }
}
