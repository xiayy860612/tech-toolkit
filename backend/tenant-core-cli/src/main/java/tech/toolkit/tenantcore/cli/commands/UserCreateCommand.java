package tech.toolkit.tenantcore.cli.commands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tech.toolkit.tenantcore.cli.ExitCodes;
import tech.toolkit.tenantcore.cli.TenantCoreCli;
import tech.toolkit.tenantcore.uam.UserService.UserCreation;

@Command(
    name = "create",
    mixinStandardHelpOptions = true,
    description =
        "Create a User (a person) in a Tenant; the same Display Name in a Tenant is the same User.")
public class UserCreateCommand implements Callable<Integer> {

  @Option(names = "--tenant", required = true, description = "Tenant Slug")
  private String tenant;

  @Option(names = "--display-name", required = true, description = "Display Name of the User")
  private String displayName;

  private final TenantCoreCli root;

  public UserCreateCommand(TenantCoreCli root) {
    this.root = root;
  }

  @Override
  public Integer call() {
    var formatter = root.formatter();
    UserCreation created = root.core().users().create(tenant, displayName, root.actor());
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("tenant", tenant);
    fields.put("tenantUserId", created.user().tenantUserId());
    fields.put("displayName", created.user().displayName());
    formatter.result(
        created.result() == UserCreation.Result.CREATED ? "created" : "exists", fields);
    return ExitCodes.OK;
  }
}
