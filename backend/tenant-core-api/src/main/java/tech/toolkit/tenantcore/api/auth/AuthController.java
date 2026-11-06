package tech.toolkit.tenantcore.api.auth;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.uam.AuthService.LoginResult;
import tech.toolkit.tenantcore.uam.AuthService.Me;
import tech.toolkit.tenantcore.uam.AuthenticatedPrincipal;

/** Login, logout, password change and "who am I". Bodies follow contracts/tenant-core-api.yaml. */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

  public record LoginBody(String tenantSlug, String username, String password) {}

  public record ChangePasswordBody(String currentPassword, String newPassword) {}

  public record LoginResponse(String token, Instant expiresAt, boolean mustChangePassword) {}

  private final CoreComponents core;

  public AuthController(CoreComponents core) {
    this.core = core;
  }

  @PostMapping("/login")
  public LoginResponse login(@RequestBody LoginBody body) {
    LoginResult result =
        core.auth()
            .login(
                required(body.tenantSlug(), "tenantSlug"),
                required(body.username(), "username"),
                required(body.password(), "password"));
    return toResponse(result);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@RequestHeader("X-Session-Id") String sessionId) {
    core.auth().logout(sessionId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/change-password")
  public LoginResponse changePassword(
      @RequestHeader("X-Session-Id") String sessionId, @RequestBody ChangePasswordBody body) {
    return toResponse(
        core.auth()
            .changePassword(
                sessionId,
                required(body.currentPassword(), "currentPassword"),
                required(body.newPassword(), "newPassword")));
  }

  @GetMapping("/me")
  public Me me(
      @RequestAttribute(RequestAuthenticationFilter.PRINCIPAL) AuthenticatedPrincipal principal) {
    return core.auth().me(principal);
  }

  private static LoginResponse toResponse(LoginResult result) {
    return new LoginResponse(result.token(), result.expiresAt(), result.mustChangePassword());
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new DomainException(ErrorCode.VALIDATION, field + " is required");
    }
    return value;
  }
}
