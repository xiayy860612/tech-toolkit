package tech.toolkit.tenantcore.cli.commands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tech.toolkit.tenantcore.cli.ExitCodes;
import tech.toolkit.tenantcore.cli.TenantCoreCli;
import tech.toolkit.tenantcore.uam.LoginMethodService.EnableResult;

@Command(
    name = "enable",
    mixinStandardHelpOptions = true,
    description = "Enable a Login Method (only password) for a Tenant (idempotent).")
public class LoginMethodEnableCommand implements Callable<Integer> {

  @Option(names = "--tenant", required = true, description = "Tenant Slug")
  private String tenant;

  @Option(names = "--type", required = true, description = "Login Method type: password")
  private String type;

  private final TenantCoreCli root;

  public LoginMethodEnableCommand(TenantCoreCli root) {
    this.root = root;
  }

  @Override
  public Integer call() {
    var formatter = root.formatter();
    EnableResult result = root.core().loginMethods().enable(tenant, type, root.actor());
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("tenant", tenant);
    fields.put("type", "password");
    formatter.result(result == EnableResult.ENABLED ? "enabled" : "already_enabled", fields);
    return ExitCodes.OK;
  }
}
