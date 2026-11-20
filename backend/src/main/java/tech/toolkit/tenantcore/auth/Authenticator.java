package tech.toolkit.tenantcore.auth;

import tech.toolkit.tenantcore.identity.AppUser;

/** 按登录方式分派的凭证校验策略。新增登录方式（手机号、微信、OAuth2.0 …）= 新枚举值 + 新的 Account 表 + 新实现，不改 User 定义（SC-007）。 */
public interface Authenticator {

  LoginType type();

  /** 校验该用户的凭证；{@code secret} 为登录时提交的密码/验证码等。 */
  boolean verify(AppUser user, String secret);

  /** 用户不存在时做一次等价的耗时计算，避免通过耗时区分用户是否存在。 */
  void burnTime(String secret);
}
