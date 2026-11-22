package tech.toolkit.auth.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * FR-004: a client may demand a fresh login ({@code prompt=login}, or {@code max_age} older than
 * the current login). The existing login is then dropped for this request, which sends the user to
 * the login page, and once they have signed in the very same request is let through (it must not
 * ask again, or it would loop).
 */
public class ReauthenticationFilter extends OncePerRequestFilter {

  static final String AUTH_TIME = "auth.authTime";
  private static final String PENDING = "auth.reauth.pending";
  private static final String SATISFIED = "auth.reauth.satisfied";

  private final RequestMatcher authorizeEndpoint =
      new OrRequestMatcher(
          PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/oauth2/authorize"),
          PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/oauth2/authorize"));
  private final Clock clock;

  public ReauthenticationFilter(Clock clock) {
    this.clock = clock;
  }

  /** Called when a login succeeds: remembers when, and unlocks the request that asked for it. */
  static void onLogin(HttpSession session, Instant now) {
    session.setAttribute(AUTH_TIME, now);
    Object pending = session.getAttribute(PENDING);
    if (pending != null) {
      session.setAttribute(SATISFIED, pending);
      session.removeAttribute(PENDING);
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !authorizeEndpoint.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (session == null
        || authentication == null
        || authentication instanceof AnonymousAuthenticationToken
        || !authentication.isAuthenticated()) {
      chain.doFilter(request, response);
      return;
    }
    String key = requestKey(request);
    if (key.equals(session.getAttribute(SATISFIED))) {
      session.removeAttribute(SATISFIED);
    } else if (freshLoginRequired(request, session)) {
      session.setAttribute(PENDING, key);
      session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
      SecurityContextHolder.clearContext();
    }
    chain.doFilter(request, response);
  }

  private boolean freshLoginRequired(HttpServletRequest request, HttpSession session) {
    String prompt = request.getParameter("prompt");
    if (prompt != null && Arrays.asList(prompt.trim().split("\\s+")).contains("login")) {
      return true;
    }
    String maxAge = request.getParameter("max_age");
    if (maxAge == null) {
      return false;
    }
    try {
      long seconds = Long.parseLong(maxAge.trim());
      Instant authTime = (Instant) session.getAttribute(AUTH_TIME);
      return authTime == null
          || Duration.between(authTime, clock.instant()).compareTo(Duration.ofSeconds(seconds)) > 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** Identifies a request across the redirect to the login page, which appends {@code continue}. */
  private static String requestKey(HttpServletRequest request) {
    Map<String, String> sorted = new TreeMap<>();
    request
        .getParameterMap()
        .forEach(
            (name, values) -> {
              if (!"continue".equals(name)) {
                sorted.put(name, String.join(",", values));
              }
            });
    return Integer.toHexString(sorted.hashCode());
  }
}
