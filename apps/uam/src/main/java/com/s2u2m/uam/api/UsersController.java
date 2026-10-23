package com.s2u2m.uam.api;

import com.s2u2m.uam.api.dto.UserDetail;
import com.s2u2m.uam.api.dto.UserPage;
import com.s2u2m.uam.application.GetUserUseCase;
import com.s2u2m.uam.application.ListUsersUseCase;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户查询（契约 {@code /v1/users}）：列表（分页、搜索）与详情。读操作，操作人请求头可选。 */
@RestController
@RequestMapping("/v1/users")
@Profile("!cli")
public class UsersController {

  private final ListUsersUseCase listUsers;
  private final GetUserUseCase getUser;

  public UsersController(ListUsersUseCase listUsers, GetUserUseCase getUser) {
    this.listUsers = listUsers;
    this.getUser = getUser;
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
}
