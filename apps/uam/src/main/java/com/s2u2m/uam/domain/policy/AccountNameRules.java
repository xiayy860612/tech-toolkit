package com.s2u2m.uam.domain.policy;

import com.s2u2m.uam.domain.DomainException.FieldError;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 账号名规则（FR-022）：3–32 个字母、数字或 {@code _ . -}；大小写不敏感由 {@code LoginName.normalized} 保证。
 *
 * <p>「字母」取 ASCII：规范化用 {@code lower()}，ASCII 在 Java 与 PostgreSQL 下的结果一致，不会因区域或 Unicode 大小写规则产生歧义。
 */
public final class AccountNameRules {

  public static final String FIELD = "accountName";
  public static final int MIN_LENGTH = 3;
  public static final int MAX_LENGTH = 32;

  private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_.-]+");

  private AccountNameRules() {}

  /** 返回字段级错误（空列表表示合规）；原因取 {@code required / too_short / too_long / invalid_chars}。 */
  public static List<FieldError> validate(String name) {
    if (name == null || name.isBlank()) {
      return List.of(new FieldError(FIELD, "required"));
    }
    if (!ALLOWED.matcher(name).matches()) {
      return List.of(new FieldError(FIELD, "invalid_chars"));
    }
    if (name.length() < MIN_LENGTH) {
      return List.of(new FieldError(FIELD, "too_short"));
    }
    if (name.length() > MAX_LENGTH) {
      return List.of(new FieldError(FIELD, "too_long"));
    }
    return List.of();
  }
}
