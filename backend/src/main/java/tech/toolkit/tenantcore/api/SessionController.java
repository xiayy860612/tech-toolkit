package tech.toolkit.tenantcore.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.api.SessionDtos.LoginSessionPage;
import tech.toolkit.tenantcore.api.security.AuthenticatedUser;
import tech.toolkit.tenantcore.auth.SessionAdminService;

@RestController
public class SessionController {

  private final SessionAdminService service;

  public SessionController(SessionAdminService service) {
    this.service = service;
  }

  @GetMapping("/sessions")
  LoginSessionPage list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletRequest request) {
    PageParams p = PageParams.of(page, size);
    UUID current = AuthenticatedUser.of(request).sessionId();
    return LoginSessionPage.of(
        service.list(PageParams.keyword(keyword), current, p.page(), p.size()));
  }

  @DeleteMapping("/sessions/{sessionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@PathVariable UUID sessionId, HttpServletRequest request) {
    service.revoke(Actors.of(request), AuthenticatedUser.of(request).sessionId(), sessionId);
  }

  @DeleteMapping("/users/{userId}/sessions")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revokeAllOfUser(@PathVariable UUID userId, HttpServletRequest request) {
    service.revokeAllOfUser(Actors.of(request), userId);
  }
}
