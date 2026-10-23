package com.s2u2m.uam.api;

import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.ClientIdentity;
import com.s2u2m.uam.domain.port.ClientAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 客户端认证：每个接口都先校验 {@code Authorization: Bearer <服务密钥>}（research R4）。
 *
 * <p>缺失、无效一律 401 {@code client_unauthenticated}，响应不透露是哪一部分不对，也不回显提交的密钥。本过滤器只做识别与 401；权限 403、
 * 双密钥轮换与拒绝日志在 US12。仅存活 / 就绪探针 {@code /actuator/health/**} 免校验（编排器需要，且只暴露 UP / DOWN）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ClientAuthFilter extends OncePerRequestFilter {

  /** 请求属性：已识别客户端的 {@code id}。 */
  public static final String CLIENT_ID_ATTRIBUTE = "uam.clientId";

  private static final String BEARER = "bearer ";

  private final ClientAuthenticator authenticator;
  private final JsonMapper json;

  public ClientAuthFilter(ClientAuthenticator authenticator, JsonMapper json) {
    this.authenticator = authenticator;
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return "/actuator/health".equals(uri) || uri.startsWith("/actuator/health/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<ClientIdentity> client = serviceKey(request).flatMap(authenticator::authenticate);
    if (client.isEmpty()) {
      reject(response);
      return;
    }
    request.setAttribute(CLIENT_ID_ATTRIBUTE, client.get().id());
    chain.doFilter(request, response);
  }

  private static Optional<String> serviceKey(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.toLowerCase(Locale.ROOT).startsWith(BEARER)) {
      return Optional.empty();
    }
    String token = header.substring(BEARER.length()).trim();
    return token.isEmpty() ? Optional.empty() : Optional.of(token);
  }

  private void reject(HttpServletResponse response) throws IOException {
    Problem problem = Problem.of(ErrorCode.CLIENT_UNAUTHENTICATED, "服务密钥缺失或无效");
    response.setStatus(problem.status());
    response.setContentType(Problem.MEDIA_TYPE);
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    response.getOutputStream().write(json.writeValueAsBytes(problem));
  }
}
