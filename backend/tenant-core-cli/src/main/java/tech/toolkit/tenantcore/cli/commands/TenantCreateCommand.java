package tech.toolkit.tenantcore.cli.commands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tech.toolkit.tenantcore.cli.ExitCodes;
import tech.toolkit.tenantcore.cli.TenantCoreCli;
import tech.toolkit.tenantcore.tenant.TenantService.TenantCreation;

@Command(
    name = "create",
    mixinStandardHelpOptions = true,
    description = "Create a Tenant (idempotent).")
public class TenantCreateCommand implements Callable<Integer> {

  @Option(names = "--slug", required = true, description = "Tenant Slug, e.g. acme")
  private String slug;

  @Option(names = "--name", required = true, description = "Tenant name")
  private String name;

  private final TenantCoreCli root;

  public TenantCreateCommand(TenantCoreCli root) {
    this.root = root;
  }

  @Override
  public Integer call() {
    var formatter = root.formatter();
    TenantCreation created = root.core().tenants().create(slug, name, root.actor());
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("tenant", created.tenant().slug());
    fields.put("name", created.tenant().name());
    formatter.result(
        created.result() == TenantCreation.Result.CREATED ? "created" : "exists", fields);
    return ExitCodes.OK;
  }
}
