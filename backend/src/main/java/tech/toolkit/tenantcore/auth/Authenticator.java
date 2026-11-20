package tech.toolkit.tenantcore.auth;

import tech.toolkit.tenantcore.identity.Account;
import tech.toolkit.tenantcore.identity.AccountType;

/** 按 Account 类型分派的凭证校验策略。新增登录方式（邮箱验证码、SSO …）= 新枚举值 + 新凭证表 + 新实现， 不改 User（SC-007）。 */
public interface Authenticator {

  AccountType type();

  /** 校验该 Account 的凭证；{@code secret} 为登录时提交的密码/验证码等。 */
  boolean verify(Account account, String secret);

  /** 账号不存在时做一次等价的耗时计算，避免通过耗时区分账号是否存在。 */
  void burnTime(String secret);
}
