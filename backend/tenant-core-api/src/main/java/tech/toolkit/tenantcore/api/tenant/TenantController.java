package tech.toolkit.tenantcore.api.tenant;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.Page;

@RestController
public class TenantController {

  /** A Tenant as shown in the console; the internal id is never exposed. */
  public record TenantView(String slug, String name, String status, Instant createdAt) {}

  private final CoreComponents core;

  public TenantController(CoreComponents core) {
    this.core = core;
  }

  @GetMapping("/v1/tenants")
  public Page<TenantView> tenants(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return core.tenantQueries()
        .tenants(page, size)
        .map(t -> new TenantView(t.slug(), t.name(), t.status(), t.createdAt()));
  }
}
