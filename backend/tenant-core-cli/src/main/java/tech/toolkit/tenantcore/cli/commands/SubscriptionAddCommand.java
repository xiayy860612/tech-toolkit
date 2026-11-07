package tech.toolkit.tenantcore.cli.commands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tech.toolkit.tenantcore.cli.ExitCodes;
import tech.toolkit.tenantcore.cli.TenantCoreCli;
import tech.toolkit.tenantcore.tenant.SubscriptionService.SubscriptionResult;

@Command(
    name = "add",
    mixinStandardHelpOptions = true,
    description = "Subscribe a Tenant to a Module (MVP: platform), idempotent.")
public class SubscriptionAddCommand implements Callable<Integer> {

  @Option(names = "--tenant", required = true, description = "Tenant Slug")
  private String tenant;

  @Option(names = "--module", required = true, description = "Module code: platform")
  private String module;

  private final TenantCoreCli root;

  public SubscriptionAddCommand(TenantCoreCli root) {
    this.root = root;
  }

  @Override
  public Integer call() {
    var formatter = root.formatter();
    SubscriptionResult result = root.core().subscriptions().add(tenant, module, root.actor());
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("tenant", tenant);
    fields.put("module", module);
    formatter.result(
        result == SubscriptionResult.SUBSCRIBED ? "subscribed" : "already_subscribed", fields);
    return ExitCodes.OK;
  }
}
