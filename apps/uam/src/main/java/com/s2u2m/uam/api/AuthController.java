package com.s2u2m.uam.api;

import com.s2u2m.uam.api.dto.LoginRequest;
import com.s2u2m.uam.api.dto.LoginResponse;
import com.s2u2m.uam.api.dto.VerifyRequest;
import com.s2u2m.uam.api.dto.VerifyResponse;
import com.s2u2m.uam.application.LoginCommand;
import com.s2u2m.uam.application.LoginUseCase;
import com.s2u2m.uam.application.LogoutUseCase;
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
 * 登录、校验会话与退出（契约 {@code /v1/auth/*}）。UAM 不使用 cookie 或任何用户凭据来识别调用方（也不认证调用方，只靠网络隔离）：
 * 会话标识只作为本处的输入出现，响应带 {@code Cache-Control: no-store}。
 */
@RestController
@RequestMapping("/v1/auth")
@Profile("!cli")
public class AuthController {

  private final LoginUseCase login;
  private final VerifySessionUseCase verify;
  private final LogoutUseCase logout;

  public AuthController(LoginUseCase login, VerifySessionUseCase verify, LogoutUseCase logout) {
    this.login = login;
    this.verify = verify;
    this.logout = logout;
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

  /** 退出：幂等，无论会话是否存在一律 204，无响应体。 */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@RequestBody VerifyRequest request) {
    logout.logout(request.sessionId());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
