package tech.toolkit.tenantcore.api.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import tech.toolkit.tenantcore.identity.Role;

/**
 * 通过校验的当前用户。{@code role} 与 {@code mustChangePassword} 取自数据库当前值，不来自令牌。 {@code sessionId} 即 JWT 的
 * {@code sid}，用于判断"当前会话"。
 */
public record AuthenticatedUser(
    UUID userId,
    UUID accountId,
    UUID sessionId,
    long tenantId,
    Role role,
    boolean mustChangePassword,
    String clientIp) {

  static final String ATTRIBUTE = AuthenticatedUser.class.getName();

  public static AuthenticatedUser of(HttpServletRequest request) {
    return (AuthenticatedUser) request.getAttribute(ATTRIBUTE);
  }
}
