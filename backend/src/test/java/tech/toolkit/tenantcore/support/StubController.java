package tech.toolkit.tenantcore.support;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.platform.TenantContext;

/**
 * 仅用于 JwtAuthenticationFilterIT：不依赖后续阶段的真实端点。仅在 {@code stub} profile 启用； 真实的 AuthController 标注为
 * {@code @Profile("!stub")}，因此不会冲突。
 */
@RestController
@Profile("stub")
public class StubController {

  @PostMapping("/auth/login")
  Map<String, String> login() {
    return Map.of("ok", "login");
  }

  @PostMapping("/auth/sessions/resolve")
  Map<String, String> resolve() {
    return Map.of("ok", "resolve");
  }

  @GetMapping("/auth/me")
  Map<String, String> me() {
    return Map.of("ok", "me");
  }

  @PostMapping("/auth/change-password")
  Map<String, String> changePassword() {
    return Map.of("ok", "change-password");
  }

  @PostMapping("/auth/logout")
  Map<String, String> logout() {
    return Map.of("ok", "logout");
  }

  @GetMapping("/_test/admin")
  Map<String, String> admin() {
    return Map.of("ok", "admin");
  }

  @GetMapping("/_test/tenant")
  Map<String, Object> tenant() {
    return Map.of("tenant", TenantContext.current().orElse(-99L));
  }
}
