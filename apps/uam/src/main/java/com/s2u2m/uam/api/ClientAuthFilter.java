package com.s2u2m.uam.api;

import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.ClientIdentity;
import com.s2u2m.uam.domain.model.ClientRejection;
import com.s2u2m.uam.domain.model.ClientRejection.Reason;
import com.s2u2m.uam.domain.port.ClientAuthenticator;
import com.s2u2m.uam.domain.port.ClientRejectionLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 客户端认证与授权：每个接口都先校验 {@code Authorization: Bearer <服务密钥>}（research R4）。
 *
 * <p>缺失、格式不对、无效、已撤销一律 401 {@code client_unauthenticated}，响应不透露是哪一部分不对，也不回显提交的密钥。密钥有效但客户端没有该接口所需的权限
 * （{@link ClientPermissions}）→ 403 {@code client_forbidden}。每次拒绝都写一条拒绝日志（原因类别、来源地址，不含密钥）。仅存活 / 就绪探针
 * {@code /actuator/health/**} 免校验（编排器需要，且只暴露 UP / DOWN）。
 */
@Component
@Profile("!cli")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ClientAuthFilter extends OncePerRequestFilter {

  /** 请求属性：已识别客户端的 {@code id}。 */
  public static final String CLIENT_ID_ATTRIBUTE = "uam.clientId";

  private static final String BEARER = "bearer ";

  private final ClientAuthenticator authenticator;
  private final JsonMapper json;
  private final ClientRejectionLog rejectionLog;

  public ClientAuthFilter(
      ClientAuthenticator authenticator, JsonMapper json, ClientRejectionLog rejectionLog) {
    this.authenticator = authenticator;
    this.json = json;
    this.rejectionLog = rejectionLog;
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
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null) {
      unauthenticated(request, response, Reason.MISSING_KEY);
      return;
    }
    Optional<String> key = serviceKey(header);
    if (key.isEmpty()) {
      unauthenticated(request, response, Reason.MALFORMED);
      return;
    }
    Optional<ClientIdentity> client = authenticator.authenticate(key.get());
    if (client.isEmpty()) {
      unauthenticated(request, response, Reason.UNKNOWN_KEY);
      return;
    }
    Optional<String> required = ClientPermissions.requiredFor(request.getMethod());
    if (required.isPresent() && !client.get().has(required.get())) {
      forbidden(request, response, client.get(), required.get());
      return;
    }
    request.setAttribute(CLIENT_ID_ATTRIBUTE, client.get().id());
    chain.doFilter(request, response);
  }

  private static Optional<String> serviceKey(String header) {
    if (!header.toLowerCase(Locale.ROOT).startsWith(BEARER)) {
      return Optional.empty();
    }
    String token = header.substring(BEARER.length()).trim();
    return token.isEmpty() ? Optional.empty() : Optional.of(token);
  }

  private void unauthenticated(
      HttpServletRequest request, HttpServletResponse response, Reason reason) throws IOException {
    Problem problem = Problem.of(ErrorCode.CLIENT_UNAUTHENTICATED, "服务密钥缺失或无效");
    log(request, reason, null, problem.status());
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    write(response, problem);
  }

  private void forbidden(
      HttpServletRequest request,
      HttpServletResponse response,
      ClientIdentity client,
      String permission)
      throws IOException {
    Problem problem = Problem.of(ErrorCode.CLIENT_FORBIDDEN, "客户端未被授予权限：" + permission);
    log(request, Reason.FORBIDDEN, client.id(), problem.status());
    write(response, problem);
  }

  private void log(HttpServletRequest request, Reason reason, String clientId, int status) {
    rejectionLog.record(
        new ClientRejection(
            reason,
            request.getRemoteAddr(),
            request.getMethod(),
            request.getRequestURI(),
            clientId,
            status));
  }

  private void write(HttpServletResponse response, Problem problem) throws IOException {
    response.setStatus(problem.status());
    response.setContentType(Problem.MEDIA_TYPE);
    response.getOutputStream().write(json.writeValueAsBytes(problem));
  }
}
