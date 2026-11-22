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
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * FR-002a: a login form may only be submitted within a limited time after the login page was shown.
 * A late or page-less submission is not processed at all (the credentials are never checked); it is
 * forwarded to the "login request expired" page.
 */
public class LoginRequestExpiryFilter extends OncePerRequestFilter {

  /** Session attribute holding the instant the login page was last shown. */
  static final String SHOWN_AT = "auth.loginShownAt";

  private final RequestMatcher loginSubmission =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login");
  private final Clock clock;
  private final Duration ttl;

  public LoginRequestExpiryFilter(Clock clock, Duration ttl) {
    this.clock = clock;
    this.ttl = ttl;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !loginSubmission.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    Instant shownAt = session == null ? null : (Instant) session.getAttribute(SHOWN_AT);
    if (shownAt == null || shownAt.plus(ttl).isBefore(clock.instant())) {
      request.getRequestDispatcher("/login/expired").forward(request, response);
      return;
    }
    chain.doFilter(request, response);
  }
}
