package tech.toolkit.tenantcore.api.auth;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.CoreComponents;

/**
 * Public key set for verifying Internal JWTs. It contains only public material; in a cluster it is
 * reachable from inside the network only (NetworkPolicy, see
 * docs/system-design/02-authentication.md).
 */
@RestController
public class JwksController {

  private final CoreComponents core;

  public JwksController(CoreComponents core) {
    this.core = core;
  }

  @GetMapping("/.well-known/jwks.json")
  public Map<String, Object> jwks() {
    return core.jwks().jwks();
  }
}
