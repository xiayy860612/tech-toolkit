package tech.toolkit.tenantcore.api.tenant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.Page;
import tech.toolkit.tenantcore.tenant.ModuleSubscription;

@RestController
public class ModuleSubscriptionController {

  private final CoreComponents core;

  public ModuleSubscriptionController(CoreComponents core) {
    this.core = core;
  }

  @GetMapping("/v1/module-subscriptions")
  public Page<ModuleSubscription.View> subscriptions(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return core.tenantQueries().subscriptions(page, size);
  }
}
