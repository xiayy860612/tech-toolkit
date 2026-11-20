package tech.toolkit.tenantcore.auth;

/** 登录方式。首期只有用户名密码（内置于 User）；日后新增手机号、微信、OAuth2.0 等 Account 类型时在此扩展。 */
public enum LoginType {
  USERNAME_PASSWORD
}
