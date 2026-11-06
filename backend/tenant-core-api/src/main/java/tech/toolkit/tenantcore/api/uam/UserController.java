package tech.toolkit.tenantcore.api.uam;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.Page;
import tech.toolkit.tenantcore.uam.UserQueryService.UserView;

@RestController
public class UserController {

  private final CoreComponents core;

  public UserController(CoreComponents core) {
    this.core = core;
  }

  /** Users with the usernames of their Accounts; contains no credential data. */
  @GetMapping("/v1/users")
  public Page<UserView> users(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return core.userQueries().users(page, size);
  }
}
