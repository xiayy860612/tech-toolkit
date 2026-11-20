package tech.toolkit.tenantcore.platform;

import java.util.UUID;

/**
 * BFF 签发的内部 JWT 声明。{@code USER} 令牌携带当前用户、租户与会话 id；{@code SERVICE} 令牌只含租户 （BFF
 * 的默认租户），仅用于登录与会话解析。注意：令牌不携带角色，角色一律读数据库。
 */
public record InternalJwtClaims(
    Type type,
    String jti,
    long tenantId,
    UUID userId,
    UUID accountId,
    UUID sessionId,
    String clientIp) {

  public enum Type {
    USER,
    SERVICE
  }
}
