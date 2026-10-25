package com.s2u2m.uam.infrastructure.logging;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 统一脱敏层：保证密码、密码哈希、会话标识、服务密钥永不进入日志（SC-014）。
 *
 * <p>两道防线：按键名（{@link #isSensitiveKey}）整体遮蔽；按内容（{@link #redact}）识别哈希、服务密钥、Bearer 凭据、 {@code
 * key=value} 形式的机密与 43 位以上的不透明令牌（256 位会话标识的 base64url 长度）。
 */
public final class LogRedaction {

  public static final String MASK = "[REDACTED]";

  private static final Set<String> SENSITIVE_KEYS =
      Set.of(
          "password",
          "currentpassword",
          "newpassword",
          "passwordhash",
          "sessionid",
          "servicekey",
          "authorization",
          "token",
          "secret");

  private static final Pattern DELEGATING_HASH =
      Pattern.compile("\\{[A-Za-z0-9@_.-]+\\}\\$[^\\s\"']+");
  private static final Pattern RAW_HASH =
      Pattern.compile("\\$(?:argon2(?:id|i|d)|2[abxy])\\$[^\\s\"']+");
  private static final Pattern KEY_HASH = Pattern.compile("sha256:[0-9a-fA-F]{64}");
  private static final Pattern SERVICE_KEY = Pattern.compile("uamk_[A-Za-z0-9_-]+");
  private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[^\\s\"',;]+");
  private static final Pattern KEYED_SECRET =
      Pattern.compile(
          "(?i)(\"?(?:current_?password|new_?password|password_?hash|password|session_?id"
              + "|service_?key|authorization|token|secret)\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^\\s,;&]+)");
  private static final Pattern OPAQUE_TOKEN =
      Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43,}(?![A-Za-z0-9_-])");

  private LogRedaction() {}

  /** 键名是否表示机密（忽略大小写与 {@code _} / {@code -}）。 */
  public static boolean isSensitiveKey(String key) {
    return key != null
        && SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", ""));
  }

  /** 日志字段的最终取值：敏感键整体遮蔽，其余按内容脱敏；{@code null} 保持 {@code null}。 */
  public static String valueFor(String key, String value) {
    if (value == null) {
      return null;
    }
    return isSensitiveKey(key) ? MASK : redact(value);
  }

  /** 按内容脱敏一段文本；{@code null} 保持 {@code null}。 */
  public static String redact(String text) {
    if (text == null) {
      return null;
    }
    String result = DELEGATING_HASH.matcher(text).replaceAll(MASK);
    result = RAW_HASH.matcher(result).replaceAll(MASK);
    result = KEY_HASH.matcher(result).replaceAll(MASK);
    result = SERVICE_KEY.matcher(result).replaceAll(MASK);
    result = BEARER.matcher(result).replaceAll("Bearer " + MASK);
    result = KEYED_SECRET.matcher(result).replaceAll("$1" + MASK);
    return OPAQUE_TOKEN.matcher(result).replaceAll(MASK);
  }
}
