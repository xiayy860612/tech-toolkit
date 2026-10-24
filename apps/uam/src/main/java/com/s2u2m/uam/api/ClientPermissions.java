package com.s2u2m.uam.api;

import com.s2u2m.uam.domain.model.ClientIdentity;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 接口所需客户端权限的唯一决策点（research R4「权限」）。
 *
 * <p>只读查询（GET / HEAD）只需有效的服务密钥；其余——登录、校验会话、退出、修改密码与全部写操作，它们都带着或断言终端用户 / 操作人上下文—— 需要 {@link
 * ClientIdentity#ASSERT_END_USER_CONTEXT}。按 HTTP 方法而非路径决定，因此新增的写接口默认受限（失败即关闭）；契约测试 {@code
 * ClientAuthContractIT} 遍历契约的每个接口固定这一决定。
 */
public final class ClientPermissions {

  private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD");

  private ClientPermissions() {}

  /** 调用该方法的接口所需的权限；空表示只需有效密钥。 */
  public static Optional<String> requiredFor(String method) {
    if (method != null && READ_ONLY_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
      return Optional.empty();
    }
    return Optional.of(ClientIdentity.ASSERT_END_USER_CONTEXT);
  }
}
