package tech.toolkit.auth.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session once it is older than the absolute limit (default 8 hours, FR-003), however active
 * it is. The idle limit is handled by the session store itself.
 */
public class AbsoluteTimeoutFilter extends OncePerRequestFilter {

  private final Clock clock;
  private final Duration limit;

  public AbsoluteTimeoutFilter(Clock clock, Duration limit) {
    this.clock = clock;
    this.limit = limit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    if (session != null
        && Instant.ofEpochMilli(session.getCreationTime()).plus(limit).isBefore(clock.instant())) {
      session.invalidate();
    }
    chain.doFilter(request, response);
  }
}
