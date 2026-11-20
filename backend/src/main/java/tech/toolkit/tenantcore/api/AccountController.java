package tech.toolkit.tenantcore.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.api.AccountDtos.Account;
import tech.toolkit.tenantcore.api.AccountDtos.AccountWithTemporaryPassword;
import tech.toolkit.tenantcore.api.AccountDtos.CreateAccountRequest;
import tech.toolkit.tenantcore.api.AccountDtos.ResetPasswordRequest;
import tech.toolkit.tenantcore.identity.AccountService;

@RestController
@org.springframework.web.bind.annotation.RequestMapping("/users/{userId}/accounts")
public class AccountController {

  private final AccountService accounts;
  private final Clock clock;

  public AccountController(AccountService accounts, Clock clock) {
    this.accounts = accounts;
    this.clock = clock;
  }

  @GetMapping
  List<Account> list(@PathVariable UUID userId) {
    return accounts.list(userId).stream().map(a -> Account.of(a, clock.instant())).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  AccountWithTemporaryPassword create(
      @PathVariable UUID userId,
      @Valid @RequestBody CreateAccountRequest body,
      HttpServletRequest request) {
    var created =
        accounts.create(Actors.of(request), userId, body.username(), body.temporaryPassword());
    return AccountWithTemporaryPassword.of(
        created.account(), created.temporaryPassword(), clock.instant());
  }

  @DeleteMapping("/{accountId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(
      @PathVariable UUID userId,
      @PathVariable UUID accountId,
      @RequestParam(defaultValue = "false") boolean confirm,
      HttpServletRequest request) {
    accounts.delete(Actors.of(request), userId, accountId, confirm);
  }

  @PostMapping("/{accountId}/disable")
  Account disable(
      @PathVariable UUID userId,
      @PathVariable UUID accountId,
      @RequestParam(defaultValue = "false") boolean confirm,
      HttpServletRequest request) {
    return Account.of(
        accounts.disable(Actors.of(request), userId, accountId, confirm), clock.instant());
  }

  @PostMapping("/{accountId}/enable")
  Account enable(
      @PathVariable UUID userId, @PathVariable UUID accountId, HttpServletRequest request) {
    return Account.of(accounts.enable(Actors.of(request), userId, accountId), clock.instant());
  }

  @PostMapping("/{accountId}/unlock")
  Account unlock(
      @PathVariable UUID userId, @PathVariable UUID accountId, HttpServletRequest request) {
    return Account.of(accounts.unlock(Actors.of(request), userId, accountId), clock.instant());
  }

  @PostMapping("/{accountId}/reset-password")
  AccountWithTemporaryPassword resetPassword(
      @PathVariable UUID userId,
      @PathVariable UUID accountId,
      @RequestBody(required = false) ResetPasswordRequest body,
      HttpServletRequest request) {
    var reset =
        accounts.resetPassword(
            Actors.of(request), userId, accountId, body == null ? null : body.temporaryPassword());
    return AccountWithTemporaryPassword.of(
        reset.account(), reset.temporaryPassword(), clock.instant());
  }
}
