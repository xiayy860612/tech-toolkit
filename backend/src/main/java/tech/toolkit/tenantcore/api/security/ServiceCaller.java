package tech.toolkit.tenantcore.api.security;

import jakarta.servlet.http.HttpServletRequest;

/** {@code typ=service} 令牌的调用方（仅用于登录与会话解析）：只有租户与客户端 IP，没有用户。 */
public record ServiceCaller(long tenantId, String clientIp) {

  static final String ATTRIBUTE = ServiceCaller.class.getName();

  public static ServiceCaller of(HttpServletRequest request) {
    return (ServiceCaller) request.getAttribute(ATTRIBUTE);
  }
}
