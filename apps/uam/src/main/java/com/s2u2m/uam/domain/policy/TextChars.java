package com.s2u2m.uam.domain.policy;

/**
 * 文本字符检查：NUL（{@code \u0000}）与不成对的 UTF-16 代理项无法写入 / 比较于 PostgreSQL（会变成数据库错误 → 500）， 所以在进入仓储之前就按
 * {@code invalid_chars} 拒绝。
 */
public final class TextChars {

  private TextChars() {}

  /** 含 NUL 或不成对的代理项时返回 {@code true}；成对的代理项（如 emoji）是合法字符。 */
  public static boolean hasUnsafeChars(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\u0000') {
        return true;
      }
      if (Character.isHighSurrogate(c)) {
        if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
          i++;
        } else {
          return true;
        }
      } else if (Character.isLowSurrogate(c)) {
        return true;
      }
    }
    return false;
  }
}
