package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.model.OperatorContext;

/** 新建用户的输入：操作人与客户端用于写操作日志，{@code idempotencyKey} 为请求头原值（由用例校验）。 */
public record CreateUserCommand(
    OperatorContext operator,
    String clientId,
    String idempotencyKey,
    String userName,
    String accountName,
    String password) {

  /** 不输出密码，避免误入日志。 */
  @Override
  public String toString() {
    return "CreateUserCommand[clientId="
        + clientId
        + ", userName="
        + userName
        + ", accountName="
        + accountName
        + "]";
  }
}
