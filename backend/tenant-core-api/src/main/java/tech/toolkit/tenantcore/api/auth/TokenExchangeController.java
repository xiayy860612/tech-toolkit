package tech.toolkit.tenantcore.api.auth;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.uam.AuthService.ExchangeResult;

/** Token Exchange: an Opaque Token (X-Session-Id) is swapped for a short-lived Internal JWT. */
@RestController
public class TokenExchangeController {

  private final CoreComponents core;

  public TokenExchangeController(CoreComponents core) {
    this.core = core;
  }

  @PostMapping("/v1/auth/exchange")
  public ExchangeResult exchange(@RequestHeader("X-Session-Id") String sessionId) {
    return core.auth().exchange(sessionId);
  }
}
