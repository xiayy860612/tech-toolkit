package tech.toolkit.auth.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits login submissions per source address (FR-018, alongside the per-account lockout): over the
 * limit the request is answered with 429 and a {@code Retry-After} header before any credential is
 * looked at. Showing the login page is never limited.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

  private final RequestMatcher loginSubmission =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login");
  private final TokenBucketLimiter limiter;

  public LoginRateLimitFilter(TokenBucketLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !loginSubmission.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String source = request.getRemoteAddr();
    if (limiter.tryAcquire(source)) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(429);
    response.setHeader("Retry-After", String.valueOf(limiter.retryAfterSeconds(source)));
    response.setContentType("text/plain;charset=UTF-8");
    response.getOutputStream().write("登录尝试过于频繁，请稍后再试".getBytes(StandardCharsets.UTF_8));
  }
}
