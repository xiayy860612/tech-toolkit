package tech.toolkit.tenantcore.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.uam.AuthenticatedPrincipal;
import tech.toolkit.tenantcore.uam.AuthenticatedPrincipal.Source;

/**
 * Identifies the caller of every protected request from the headers ({@code X-Session-Id} or {@code
 * Authorization: Bearer}, see {@code PrincipalResolver}) and enforces two rules: login, logout,
 * password change and token exchange accept an Opaque Token only, and a Session that still has to
 * change its initial password may only change it, log out or ask who it is.
 */
@Component
public class RequestAuthenticationFilter extends OncePerRequestFilter {

  public static final String PRINCIPAL = "tenantcore.principal";

  private static final Set<String> PUBLIC_PATHS =
      Set.of("/v1/auth/login", "/.well-known/jwks.json");
  private static final Set<String> SESSION_ONLY =
      Set.of("/v1/auth/logout", "/v1/auth/change-password", "/v1/auth/exchange");
  private static final Set<String> ALLOWED_BEFORE_PASSWORD_CHANGE =
      Set.of("/v1/auth/change-password", "/v1/auth/logout", "/v1/auth/me");

  private final CoreComponents core;
  private final HandlerExceptionResolver exceptionResolver;

  public RequestAuthenticationFilter(
      CoreComponents core,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
    this.core = core;
    this.exceptionResolver = exceptionResolver;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return PUBLIC_PATHS.contains(path) || path.startsWith("/actuator") || path.equals("/error");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    try {
      AuthenticatedPrincipal principal =
          core.principalResolver()
              .resolve(request.getHeader("X-Session-Id"), request.getHeader("Authorization"));
      if (SESSION_ONLY.contains(path) && principal.source() != Source.OPAQUE) {
        throw new DomainException(ErrorCode.UNAUTHORIZED, "This endpoint requires X-Session-Id");
      }
      if (!ALLOWED_BEFORE_PASSWORD_CHANGE.contains(path)
          && core.auth().passwordChangeRequired(principal)) {
        throw new DomainException(
            ErrorCode.PASSWORD_CHANGE_REQUIRED, "The initial password must be changed first");
      }
      request.setAttribute(PRINCIPAL, principal);
    } catch (DomainException e) {
      exceptionResolver.resolveException(request, response, null, e);
      return;
    }
    chain.doFilter(request, response);
  }
}
