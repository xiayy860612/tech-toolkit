package com.s2u2m.uam.domain.policy;

import com.s2u2m.uam.domain.DomainException.FieldError;
import java.util.List;

/**
 * 密码强度策略（FR-022）：默认至少 10 位且同时含字母与数字，最短长度可配置。
 *
 * <p>上限 256：与登录请求的 {@code password} 上限一致，更长的密码永远无法登录，也避免超长输入拖慢哈希。
 */
public final class PasswordPolicy {

  public static final int MAX_LENGTH = 256;

  private final int minLength;

  public PasswordPolicy(int minLength) {
    this.minLength = minLength;
  }

  public int minLength() {
    return minLength;
  }

  /** 返回字段级错误（空列表表示合规）；原因取 {@code required / too_short / too_long / too_weak}，绝不回显密码。 */
  public List<FieldError> validate(String field, String password) {
    if (password == null || password.isEmpty()) {
      return List.of(new FieldError(field, "required"));
    }
    int length = password.codePointCount(0, password.length());
    if (length < minLength) {
      return List.of(new FieldError(field, "too_short"));
    }
    if (length > MAX_LENGTH) {
      return List.of(new FieldError(field, "too_long"));
    }
    boolean hasLetter = password.codePoints().anyMatch(Character::isLetter);
    boolean hasDigit = password.codePoints().anyMatch(Character::isDigit);
    return hasLetter && hasDigit ? List.of() : List.of(new FieldError(field, "too_weak"));
  }
}
