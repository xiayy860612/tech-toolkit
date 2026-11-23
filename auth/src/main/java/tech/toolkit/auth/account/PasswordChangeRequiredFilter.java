package tech.toolkit.auth.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A user whose password was set by an administrator must change it before anything else: while the
 * login is flagged, an authorization request is parked (so it can continue afterwards) and the user
 * is sent to the change-password page.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

  /** Session attribute: this login still has to change the password. */
  public static final String REQUIRED = "auth.mustChangePassword";

  private final RequestMatcher authorizeEndpoint =
      new OrRequestMatcher(
          PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/oauth2/authorize"),
          PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/oauth2/authorize"));
  private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

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
    boolean loggedIn =
        authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    if (loggedIn && session != null && Boolean.TRUE.equals(session.getAttribute(REQUIRED))) {
      requestCache.saveRequest(request, response);
      response.sendRedirect(request.getContextPath() + "/account/password");
      return;
    }
    chain.doFilter(request, response);
  }
}
