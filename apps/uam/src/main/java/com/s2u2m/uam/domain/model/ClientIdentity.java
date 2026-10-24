package com.s2u2m.uam.domain.model;

import java.util.Set;

/** 已识别的客户端（调用 UAM 的服务）：名称与被授予的权限。 */
public record ClientIdentity(String id, Set<String> permissions) {

  /** 权限：可代用户断言操作人与来源地址（登录、校验会话、退出、改密与所有写操作需要它；本期唯一的权限）。 */
  public static final String ASSERT_END_USER_CONTEXT = "assert-end-user-context";

  /** 配置里允许出现的全部权限；拼写错误的权限名会让客户端静默失去权限，所以配置加载时直接拒绝。 */
  public static final Set<String> KNOWN_PERMISSIONS = Set.of(ASSERT_END_USER_CONTEXT);

  public ClientIdentity {
    permissions = Set.copyOf(permissions);
  }

  public boolean has(String permission) {
    return permissions.contains(permission);
  }
}
