package tech.toolkit.tenantcore.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.UUID;
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
import tech.toolkit.tenantcore.api.UserDtos.CreateUserResult;
import tech.toolkit.tenantcore.api.UserDtos.UpdateUserRequest;
import tech.toolkit.tenantcore.api.UserDtos.User;
import tech.toolkit.tenantcore.api.UserDtos.UserPage;
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
    return UserPage.of(users.list(PageParams.keyword(keyword), status, p.page(), p.size()));
  }

  @PostMapping("/users")
  @ResponseStatus(HttpStatus.CREATED)
  CreateUserResult create(@Valid @RequestBody CreateUserRequest body, HttpServletRequest request) {
    var in = body.account();
    UserService.Created created =
        users.create(
            Actors.of(request),
            new UserService.NewUser(
                body.displayName(),
                body.phone(),
                body.email(),
                body.role(),
                in.username(),
                in.temporaryPassword()));
    return new CreateUserResult(
        User.of(created.user()),
        AccountDtos.AccountWithTemporaryPassword.of(
            created.account(), created.temporaryPassword(), clock.instant()));
  }

  @GetMapping("/users/{userId}")
  User get(@PathVariable UUID userId) {
    return User.of(users.get(userId));
  }

  @PatchMapping("/users/{userId}")
  User update(
      @PathVariable UUID userId,
      @Valid @RequestBody UpdateUserRequest body,
      HttpServletRequest request) {
    return User.of(
        users.update(
            Actors.of(request),
            userId,
            new UserService.Changes(
                body.displayName(), body.phone(), body.email(), body.role(), body.version())));
  }

  @PostMapping("/users/{userId}/disable")
  User disable(@PathVariable UUID userId, HttpServletRequest request) {
    return User.of(users.disable(Actors.of(request), userId));
  }

  @PostMapping("/users/{userId}/enable")
  User enable(@PathVariable UUID userId, HttpServletRequest request) {
    return User.of(users.enable(Actors.of(request), userId));
  }
}
