package tech.toolkit.tenantcore.cli.commands;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tech.toolkit.tenantcore.cli.ExitCodes;
import tech.toolkit.tenantcore.cli.OutputFormatter;
import tech.toolkit.tenantcore.cli.TenantCoreCli;
import tech.toolkit.tenantcore.tenant.TenantDetailService.TenantDetail;
import tech.toolkit.tenantcore.tenant.TenantDetailService.UserSummary;

/** Read-only: prints what Bootstrap created for a Tenant so the operator can check it. */
@Command(
    name = "show",
    mixinStandardHelpOptions = true,
    description =
        "Show a Tenant with its Login Methods, Users (and Accounts) and Module Subscriptions.")
public class TenantShowCommand implements Callable<Integer> {

  @Option(names = "--tenant", required = true, description = "Tenant Slug")
  private String tenant;

  private final TenantCoreCli root;

  public TenantShowCommand(TenantCoreCli root) {
    this.root = root;
  }

  @Override
  public Integer call() {
    OutputFormatter formatter = root.formatter();
    TenantDetail detail = root.core().tenantDetails().show(tenant);
    if (formatter.isJson()) {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("tenant", detail.tenant().slug());
      fields.put("name", detail.tenant().name());
      fields.put("status", detail.tenant().status());
      fields.put("loginMethods", detail.loginMethods());
      fields.put(
          "users",
          detail.users().stream()
              .map(
                  u -> {
                    Map<String, Object> user = new LinkedHashMap<>();
                    user.put("tenantUserId", u.tenantUserId());
                    user.put("displayName", u.displayName());
                    user.put("accountUsernames", u.accountUsernames());
                    return user;
                  })
              .toList());
      fields.put("modules", detail.modules());
      formatter.result("ok", fields);
    } else {
      formatter.line(
          "Tenant: "
              + detail.tenant().slug()
              + " ("
              + detail.tenant().name()
              + ") ["
              + detail.tenant().status()
              + "]");
      formatter.line("Login Methods: " + joined(detail.loginMethods()));
      formatter.line("Users:");
      for (UserSummary user : detail.users()) {
        formatter.line(
            "  - "
                + user.displayName()
                + " ("
                + user.tenantUserId()
                + ") accounts: "
                + joined(user.accountUsernames()));
      }
      formatter.line("Modules: " + joined(detail.modules()));
    }
    return ExitCodes.OK;
  }

  private static String joined(List<String> values) {
    return values.isEmpty() ? "-" : String.join(", ", values);
  }
}
