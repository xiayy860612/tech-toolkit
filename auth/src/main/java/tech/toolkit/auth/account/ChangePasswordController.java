package tech.toolkit.auth.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The change-password page (FR-020), also where a forced change ends up first. */
@Controller
class ChangePasswordController {

  private final AccountSecurityService accounts;
  private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

  ChangePasswordController(AccountSecurityService accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/account/password")
  String form(HttpServletRequest request, Model model) {
    model.addAttribute("required", isRequired(request.getSession(false)));
    return "change-password";
  }

  @PostMapping("/account/password")
  String change(
      @RequestParam String currentPassword,
      @RequestParam String newPassword,
      Authentication authentication,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model)
      throws IOException {
    HttpSession session = request.getSession();
    AccountSecurityService.Change result =
        accounts.changeOwnPassword(
            Long.parseLong(authentication.getName()),
            session.getId(),
            currentPassword,
            newPassword);
    if (!result.changed()) {
      model.addAttribute("required", isRequired(session));
      model.addAttribute("problems", result.problems());
      return "change-password";
    }
    session.removeAttribute(PasswordChangeRequiredFilter.REQUIRED);
    SavedRequest saved = requestCache.getRequest(request, response);
    if (saved != null) {
      requestCache.removeRequest(request, response);
      response.sendRedirect(saved.getRedirectUrl());
    } else {
      response.sendRedirect(request.getContextPath() + "/account/password?changed");
    }
    return null;
  }

  private static boolean isRequired(HttpSession session) {
    return session != null
        && Boolean.TRUE.equals(session.getAttribute(PasswordChangeRequiredFilter.REQUIRED));
  }
}
