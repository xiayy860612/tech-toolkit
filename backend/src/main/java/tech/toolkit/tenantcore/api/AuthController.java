package tech.toolkit.tenantcore.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.api.AuthDtos.ChangePasswordRequest;
import tech.toolkit.tenantcore.api.AuthDtos.LoginRequest;
import tech.toolkit.tenantcore.api.AuthDtos.LoginResult;
import tech.toolkit.tenantcore.api.AuthDtos.Me;
import tech.toolkit.tenantcore.api.AuthDtos.SessionResolveRequest;
import tech.toolkit.tenantcore.api.AuthDtos.SessionResolveResult;
import tech.toolkit.tenantcore.api.security.AuthenticatedUser;
import tech.toolkit.tenantcore.api.security.ServiceCaller;
import tech.toolkit.tenantcore.audit.AuditAction;
import tech.toolkit.tenantcore.audit.AuditEvent;
import tech.toolkit.tenantcore.audit.AuditService;
import tech.toolkit.tenantcore.auth.LoginService;
import tech.toolkit.tenantcore.auth.PasswordChangeService;
import tech.toolkit.tenantcore.auth.RevokeReason;
import tech.toolkit.tenantcore.auth.Session;
import tech.toolkit.tenantcore.auth.SessionService;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.Status;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;

@RestController
@Profile("!stub")
public class AuthController {

  private final LoginService login;
  private final SessionService sessions;
  private final PasswordChangeService passwordChange;
  private final AuditService audit;
  private final AppUserRepository users;
  private final TransactionTemplate tx;

  public AuthController(
      LoginService login,
      SessionService sessions,
      PasswordChangeService passwordChange,
      AuditService audit,
      AppUserRepository users,
      TransactionTemplate tx) {
    this.login = login;
    this.sessions = sessions;
    this.passwordChange = passwordChange;
    this.audit = audit;
    this.users = users;
    this.tx = tx;
  }

  @PostMapping("/auth/login")
  LoginResult login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
    ServiceCaller caller = ServiceCaller.of(request);
    LoginService.LoggedIn in =
        login.login(body.username(), body.password(), caller.clientIp(), body.userAgent());
    Me me =
        new Me(
            in.user().getId(),
            caller.tenantId(),
            in.user().getDisplayName(),
            in.user().getRole(),
            in.mustChangePassword());
    return new LoginResult(me, in.token(), in.session().getAbsoluteExpiresAt());
  }

  @PostMapping("/auth/sessions/resolve")
  SessionResolveResult resolve(
      @Valid @RequestBody SessionResolveRequest body, HttpServletRequest request) {
    ServiceCaller caller = ServiceCaller.of(request);
    SessionResolveResult result =
        tx.execute(
            status -> {
              Session s = sessions.resolve(body.sessionToken()).orElse(null);
              if (s == null) {
                return null;
              }
              AppUser user = users.findById(s.getUserId()).orElse(null);
              if (user == null || user.getStatus() != Status.ACTIVE) {
                return null;
              }
              return new SessionResolveResult(
                  s.getId(),
                  user.getId(),
                  caller.tenantId(),
                  user.getDisplayName(),
                  user.isMustChangePassword(),
                  s.getAbsoluteExpiresAt());
            });
    if (result == null) {
      throw new ApiException(ErrorCode.UNAUTHORIZED);
    }
    return result;
  }

  @GetMapping("/auth/me")
  Me me(HttpServletRequest request) {
    AuthenticatedUser principal = AuthenticatedUser.of(request);
    AppUser user =
        users
            .findById(principal.userId())
            .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    return new Me(
        user.getId(),
        principal.tenantId(),
        user.getDisplayName(),
        user.getRole(),
        principal.mustChangePassword());
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(HttpServletRequest request) {
    AuthenticatedUser principal = AuthenticatedUser.of(request);
    tx.executeWithoutResult(
        status -> {
          sessions.revoke(principal.sessionId(), RevokeReason.LOGOUT);
          audit.record(
              AuditEvent.success(
                  AuditAction.LOGOUT,
                  principal.userId(),
                  AuditEvent.TargetType.USER,
                  principal.userId(),
                  principal.clientIp(),
                  Map.of()));
        });
  }

  @PostMapping("/auth/change-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
    AuthenticatedUser principal = AuthenticatedUser.of(request);
    passwordChange.change(
        principal.userId(),
        principal.sessionId(),
        body.currentPassword(),
        body.newPassword(),
        principal.clientIp());
  }
}
