package tech.toolkit.tenantcore.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.api.UserDtos.CreateUserRequest;
import tech.toolkit.tenantcore.api.UserDtos.ResetPasswordRequest;
import tech.toolkit.tenantcore.api.UserDtos.UpdateUserRequest;
import tech.toolkit.tenantcore.api.UserDtos.User;
import tech.toolkit.tenantcore.api.UserDtos.UserPage;
import tech.toolkit.tenantcore.api.UserDtos.UserWithTemporaryPassword;
import tech.toolkit.tenantcore.identity.Status;
import tech.toolkit.tenantcore.identity.UserService;

@RestController
public class UserController {

  private final UserService users;
  private final Clock clock;

  public UserController(UserService users, Clock clock) {
    this.users = users;
    this.clock = clock;
  }

  @GetMapping("/users")
  UserPage list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Status status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    PageParams p = PageParams.of(page, size);
    return UserPage.of(
        users.list(PageParams.keyword(keyword), status, p.page(), p.size()), clock.instant());
  }

  @PostMapping("/users")
  @ResponseStatus(HttpStatus.CREATED)
  UserWithTemporaryPassword create(
      @Valid @RequestBody CreateUserRequest body, HttpServletRequest request) {
    UserService.Created created =
        users.create(
            Actors.of(request),
            new UserService.NewUser(
                body.displayName(), body.role(), body.username(), body.temporaryPassword()));
    return new UserWithTemporaryPassword(
        User.of(created.user(), clock.instant()), created.temporaryPassword());
  }

  @GetMapping("/users/{userId}")
  User get(@PathVariable Long userId) {
    return User.of(users.get(userId), clock.instant());
  }

  @PatchMapping("/users/{userId}")
  User update(
      @PathVariable Long userId,
      @Valid @RequestBody UpdateUserRequest body,
      HttpServletRequest request) {
    return User.of(
        users.update(
            Actors.of(request),
            userId,
            new UserService.Changes(body.displayName(), body.role(), body.version())),
        clock.instant());
  }

  @PostMapping("/users/{userId}/disable")
  User disable(@PathVariable Long userId, HttpServletRequest request) {
    return User.of(users.disable(Actors.of(request), userId), clock.instant());
  }

  @PostMapping("/users/{userId}/enable")
  User enable(@PathVariable Long userId, HttpServletRequest request) {
    return User.of(users.enable(Actors.of(request), userId), clock.instant());
  }

  @PostMapping("/users/{userId}/unlock")
  User unlock(@PathVariable Long userId, HttpServletRequest request) {
    return User.of(users.unlock(Actors.of(request), userId), clock.instant());
  }

  @PostMapping("/users/{userId}/reset-password")
  UserWithTemporaryPassword resetPassword(
      @PathVariable Long userId,
      @RequestBody(required = false) ResetPasswordRequest body,
      HttpServletRequest request) {
    UserService.Created reset =
        users.resetPassword(
            Actors.of(request), userId, body == null ? null : body.temporaryPassword());
    return new UserWithTemporaryPassword(
        User.of(reset.user(), clock.instant()), reset.temporaryPassword());
  }
}
