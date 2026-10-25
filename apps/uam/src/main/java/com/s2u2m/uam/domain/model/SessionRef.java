package com.s2u2m.uam.domain.model;

import java.util.UUID;

/** 会话引用：页面与接口里唯一的会话名称，非机密（会话标识才是机密）。 */
public record SessionRef(UUID value) {

  public static SessionRef random() {
    return new SessionRef(UUID.randomUUID());
  }

  public static SessionRef parse(String text) {
    return new SessionRef(UUID.fromString(text));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
