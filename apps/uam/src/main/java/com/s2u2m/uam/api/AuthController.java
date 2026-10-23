package com.s2u2m.uam.api;

import com.s2u2m.uam.api.dto.LoginRequest;
import com.s2u2m.uam.api.dto.LoginResponse;
import com.s2u2m.uam.api.dto.VerifyRequest;
import com.s2u2m.uam.api.dto.VerifyResponse;
import com.s2u2m.uam.application.LoginCommand;
import com.s2u2m.uam.application.LoginUseCase;
import com.s2u2m.uam.application.VerifySessionUseCase;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录与校验会话（契约 {@code /v1/auth/*}）。UAM 不使用 cookie 或任何用户凭据来识别客户端：这两个接口与其他接口一样先过服务密钥过滤器，
 * 会话标识只作为本处的输入出现，响应带 {@code Cache-Control: no-store}。
 */
@RestController
@RequestMapping("/v1/auth")
@Profile("!cli")
public class AuthController {

  private final LoginUseCase login;
  private final VerifySessionUseCase verify;

  public AuthController(LoginUseCase login, VerifySessionUseCase verify) {
    this.login = login;
    this.verify = verify;
  }

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    var result =
        login.login(
            new LoginCommand(request.accountName(), request.password(), request.clientIp()));
    return noStore(LoginResponse.of(result));
  }

  @PostMapping("/sessions/verify")
  public ResponseEntity<VerifyResponse> verifySession(@RequestBody VerifyRequest request) {
    return noStore(VerifyResponse.of(verify.verify(request.sessionId())));
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
