package com.s2u2m.uam.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 会话标识及其哈希：标识是 256 位密码学安全随机数（base64url，43 字符），只交给浏览器；存储只保留 {@code SHA-256(标识)} 的十六进制（research R7）。
 */
public record SessionToken(String id, String hash) {

  private static final SecureRandom RANDOM = new SecureRandom();

  public static SessionToken generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new SessionToken(id, hashOf(id));
  }

  public static String hashOf(String sessionId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JDK 必须提供 SHA-256", e);
    }
  }

  /** 不输出标识，避免误入日志。 */
  @Override
  public String toString() {
    return "SessionToken[hash=" + hash + "]";
  }
}
