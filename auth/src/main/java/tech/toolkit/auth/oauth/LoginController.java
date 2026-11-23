package tech.toolkit.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** The login page (served by auth itself, FR-030) and the "login request expired" page. */
@Controller
class LoginController {

  private final Clock clock;
  private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

  LoginController(Clock clock) {
    this.clock = clock;
  }

  @GetMapping("/login")
  String login(HttpServletRequest request) {
    // starts the clock for FR-002a: the form may be submitted within the configured time
    request.getSession().setAttribute(LoginRequestExpiryFilter.SHOWN_AT, clock.instant());
    return "login";
  }

  /** Reached by forward from the expiry filter, so any method is accepted. */
  @RequestMapping("/login/expired")
  String expired(HttpServletRequest request, HttpServletResponse response, Model model) {
    SavedRequest saved = requestCache.getRequest(request, response);
    model.addAttribute("restartUrl", saved == null ? null : saved.getRedirectUrl());
    return "login-expired";
  }
}
