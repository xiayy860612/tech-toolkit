package com.s2u2m.uam.api;

import com.s2u2m.uam.api.dto.ChangePasswordRequest;
import com.s2u2m.uam.application.ChangePasswordCommand;
import com.s2u2m.uam.application.ChangePasswordUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号操作（契约 {@code /v1/accounts/{accountId}}）：修改自己的密码为写操作，必须带操作人请求头，且路径账号必须是操作人自己的账号； 来源地址取请求体的
 * {@code clientIp}（同登录，由被授予断言权限的客户端提供）。
 */
@RestController
@RequestMapping("/v1/accounts")
@Profile("!cli")
public class AccountsController {

  private final ChangePasswordUseCase changePassword;
  private final OperatorContextResolver operators;

  public AccountsController(
      ChangePasswordUseCase changePassword, OperatorContextResolver operators) {
    this.changePassword = changePassword;
    this.operators = operators;
  }

  /** 修改密码：204；只改路径里的账号，当前会话保持有效。 */
  @PostMapping("/{accountId}/password")
  public ResponseEntity<Void> changePassword(
      @PathVariable UUID accountId,
      @Valid @RequestBody ChangePasswordRequest request,
      HttpServletRequest http) {
    var operator = operators.resolve(http).orElseThrow();
    changePassword.change(
        new ChangePasswordCommand(
            operator,
            accountId,
            request.clientIp(),
            request.currentPassword(),
            request.newPassword()));
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }
}
