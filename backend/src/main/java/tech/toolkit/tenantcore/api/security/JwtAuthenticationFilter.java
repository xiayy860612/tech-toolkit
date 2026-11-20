package tech.toolkit.tenantcore.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.toolkit.tenantcore.api.ErrorCode;
import tech.toolkit.tenantcore.api.ProblemResponses;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.platform.InternalJwtClaims;
import tech.toolkit.tenantcore.platform.InternalJwtVerifier;
import tech.toolkit.tenantcore.platform.InvalidInternalJwtException;
import tech.toolkit.tenantcore.platform.TenantContext;

/**
 * 后端的唯一认证入口（后端不面向浏览器）：验签 → 设置租户上下文（只取自令牌）→ 校验会话与账号状态 → 角色闸门（数据库角色）→ 强制改密闸门。{@code typ=service}
 * 令牌只能访问登录与会话解析。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Set<String> SERVICE_ENDPOINTS =
      Set.of("/auth/login", "/auth/sessions/resolve");
  private static final Set<String> PASSWORD_CHANGE_ENDPOINTS =
      Set.of("/auth/me", "/auth/change-password", "/auth/logout");

  private final InternalJwtVerifier verifier;
  private final PrincipalLoader principals;

  public JwtAuthenticationFilter(InternalJwtVerifier verifier, PrincipalLoader principals) {
    this.verifier = verifier;
    this.principals = principals;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      ProblemResponses.write(response, ErrorCode.UNAUTHORIZED);
      return;
    }
    InternalJwtClaims claims;
    try {
      claims = verifier.verify(header.substring(7).trim());
    } catch (InvalidInternalJwtException e) {
      ProblemResponses.write(response, ErrorCode.UNAUTHORIZED);
      return;
    }

    String path = request.getRequestURI();
    TenantContext.set(claims.tenantId());
    try {
      if (claims.type() == InternalJwtClaims.Type.SERVICE) {
        if (!"POST".equals(request.getMethod()) || !SERVICE_ENDPOINTS.contains(path)) {
          ProblemResponses.write(response, ErrorCode.UNAUTHORIZED);
          return;
        }
        request.setAttribute(
            ServiceCaller.ATTRIBUTE, new ServiceCaller(claims.tenantId(), claims.clientIp()));
        chain.doFilter(request, response);
        return;
      }

      if (SERVICE_ENDPOINTS.contains(path)) {
        ProblemResponses.write(response, ErrorCode.UNAUTHORIZED); // 这些端点只接受 service 令牌
        return;
      }
      var user = principals.load(claims);
      if (user.isEmpty()) {
        ProblemResponses.write(response, ErrorCode.UNAUTHORIZED);
        return;
      }
      AuthenticatedUser principal = user.get();
      if (principal.mustChangePassword() && !PASSWORD_CHANGE_ENDPOINTS.contains(path)) {
        ProblemResponses.write(response, ErrorCode.PASSWORD_CHANGE_REQUIRED);
        return;
      }
      if (!path.startsWith("/auth/") && principal.role() != Role.TENANT_ADMIN) {
        ProblemResponses.write(response, ErrorCode.FORBIDDEN);
        return;
      }
      request.setAttribute(AuthenticatedUser.ATTRIBUTE, principal);
      chain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }
}
