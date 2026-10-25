package com.s2u2m.uam.application;

/** 登录输入；{@code clientIp} 由被授予断言权限的客户端提供（research R11）。 */
public record LoginCommand(String accountName, String password, String clientIp) {

  /** 不输出密码，避免误入日志。 */
  @Override
  public String toString() {
    return "LoginCommand[accountName=" + accountName + ", clientIp=" + clientIp + "]";
  }
}
