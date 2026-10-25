package com.s2u2m.uam.api;

import com.s2u2m.uam.api.dto.CreateUserRequest;
import com.s2u2m.uam.api.dto.UserDetail;
import com.s2u2m.uam.api.dto.UserPage;
import com.s2u2m.uam.application.CreateUserCommand;
import com.s2u2m.uam.application.CreateUserUseCase;
import com.s2u2m.uam.application.DisableUserUseCase;
import com.s2u2m.uam.application.EnableUserUseCase;
import com.s2u2m.uam.application.GetUserUseCase;
import com.s2u2m.uam.application.ListUsersUseCase;
import com.s2u2m.uam.application.UserStatusCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户查询、新建、停用与启用（契约 {@code /v1/users}）：列表（分页、搜索）与详情为读操作，操作人请求头可选；新建、停用、启用为写操作，必须带操作人请求头（新建另需 {@code
 * Idempotency-Key}）。
 */
@RestController
@RequestMapping("/v1/users")
@Profile("!cli")
public class UsersController {

  private final ListUsersUseCase listUsers;
  private final GetUserUseCase getUser;
  private final CreateUserUseCase createUser;
  private final DisableUserUseCase disableUser;
  private final EnableUserUseCase enableUser;
  private final OperatorContextResolver operators;

  public UsersController(
      ListUsersUseCase listUsers,
      GetUserUseCase getUser,
      CreateUserUseCase createUser,
      DisableUserUseCase disableUser,
      EnableUserUseCase enableUser,
      OperatorContextResolver operators) {
    this.listUsers = listUsers;
    this.getUser = getUser;
    this.createUser = createUser;
    this.disableUser = disableUser;
    this.enableUser = enableUser;
    this.operators = operators;
  }

  @GetMapping
  public UserPage list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "" + ListUsersUseCase.DEFAULT_SIZE) int size,
      @RequestParam(required = false) String query) {
    return UserPage.of(listUsers.list(page, size, query));
  }

  @GetMapping("/{userId}")
  public UserDetail get(@PathVariable UUID userId) {
    return UserDetail.of(getUser.get(userId));
  }

  /** 创建用户及其一个「账号名 / 密码」账号；{@code Idempotency-Key} 由用例校验（缺失 / 不合法为 422）。 */
  @PostMapping
  public ResponseEntity<UserDetail> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CreateUserRequest request,
      HttpServletRequest http) {
    var operator = operators.resolve(http).orElseThrow();
    var clientId = (String) http.getAttribute(ClientAuthFilter.CLIENT_ID_ATTRIBUTE);
    var result =
        createUser.create(
            new CreateUserCommand(
                operator,
                clientId,
                idempotencyKey,
                request.userName(),
                request.accountName(),
                request.password()));
    return ResponseEntity.status(HttpStatus.CREATED).body(UserDetail.of(result));
  }

  /** 停用（幂等）：其全部账号不能登录，全部会话立即结束；不能停用操作人自己、不能停用最后一个正常用户。 */
  @PostMapping("/{userId}/disable")
  public UserDetail disable(@PathVariable UUID userId, HttpServletRequest http) {
    return UserDetail.of(disableUser.disable(statusCommand(userId, http)));
  }

  /** 启用（幂等）：恢复其账号的登录能力，不恢复已结束的会话。 */
  @PostMapping("/{userId}/enable")
  public UserDetail enable(@PathVariable UUID userId, HttpServletRequest http) {
    return UserDetail.of(enableUser.enable(statusCommand(userId, http)));
  }

  private UserStatusCommand statusCommand(UUID userId, HttpServletRequest http) {
    var operator = operators.resolve(http).orElseThrow();
    var clientId = (String) http.getAttribute(ClientAuthFilter.CLIENT_ID_ATTRIBUTE);
    return new UserStatusCommand(operator, clientId, userId);
  }
}
