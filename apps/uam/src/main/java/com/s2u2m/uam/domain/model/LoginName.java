package com.s2u2m.uam.domain.model;

import java.util.Locale;

/** 账号名：{@code raw} 为创建时的原样写法，{@code normalized} 为 {@code lower(raw)}，唯一性与查找都按它（大小写不敏感）。 */
public record LoginName(String raw, String normalized) {

  public static LoginName of(String raw) {
    return new LoginName(raw, raw.toLowerCase(Locale.ROOT));
  }
}
