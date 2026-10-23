package com.s2u2m.uam.domain.model;

import java.util.Set;

/** 已识别的客户端（调用 UAM 的服务）：名称与被授予的权限。 */
public record ClientIdentity(String id, Set<String> permissions) {

  public ClientIdentity {
    permissions = Set.copyOf(permissions);
  }

  public boolean has(String permission) {
    return permissions.contains(permission);
  }
}
