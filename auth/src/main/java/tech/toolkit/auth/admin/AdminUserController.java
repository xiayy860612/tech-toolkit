package tech.toolkit.auth.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.auth.admin.AdminUserService.UserPage;
import tech.toolkit.auth.admin.AdminUserService.UserView;

/** The user part of the admin API, as specified in {@code contracts/admin-api.yaml}. */
@RestController
@RequestMapping("/admin/v1/users")
class AdminUserController {

  /** Body of {@code POST /users}. */
  record CreateUser(String username, String initialPassword, Boolean admin) {
    @Override
    public String toString() {
      return "CreateUser[username=" + username + ", admin=" + admin + "]";
    }
  }

  /** Body of {@code POST /users/{id}/password-reset}. */
  record PasswordReset(String temporaryPassword) {
    @Override
    public String toString() {
      return "PasswordReset[]";
    }
  }

  private final AdminUserService service;

  AdminUserController(AdminUserService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<UserView> create(@RequestBody CreateUser body, Authentication admin) {
    if (body == null || body.username() == null || body.initialPassword() == null) {
      throw new AdminException(HttpStatus.BAD_REQUEST, "请求无效", "username 和 initialPassword 必填");
    }
    UserView created =
        service.create(
            body.username(),
            body.initialPassword(),
            Boolean.TRUE.equals(body.admin()),
            actor(admin));
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping
  UserPage list(
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(username, status, page, size);
  }

  @GetMapping("/{id}")
  UserView get(@PathVariable long id) {
    return service.get(id);
  }

  @PostMapping("/{id}/disable")
  ResponseEntity<Void> disable(@PathVariable long id, Authentication admin) {
    service.disable(id, actor(admin));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/enable")
  ResponseEntity<Void> enable(@PathVariable long id, Authentication admin) {
    service.enable(id, actor(admin));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/unlock")
  ResponseEntity<Void> unlock(@PathVariable long id, Authentication admin) {
    service.unlock(id, actor(admin));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/password-reset")
  ResponseEntity<Void> resetPassword(
      @PathVariable long id, @RequestBody PasswordReset body, Authentication admin) {
    if (body == null || body.temporaryPassword() == null) {
      throw new AdminException(HttpStatus.BAD_REQUEST, "请求无效", "temporaryPassword 必填");
    }
    service.resetPassword(id, body.temporaryPassword(), actor(admin));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}/sessions")
  ResponseEntity<Void> forceSignOut(@PathVariable long id, Authentication admin) {
    service.forceSignOut(id, actor(admin));
    return ResponseEntity.noContent().build();
  }

  /** The administrator is the subject of the access token they called with. */
  private static long actor(Authentication admin) {
    return Long.parseLong(admin.getName());
  }
}
