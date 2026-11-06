package tech.toolkit.tenantcore.cli.commands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tech.toolkit.tenantcore.cli.ExitCodes;
import tech.toolkit.tenantcore.cli.TenantCoreCli;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.uam.AccountService.AccountCreation;

/**
 * Creates the username/password Account of an existing User. There is deliberately no {@code
 * --password} option: the password is read from a hidden prompt or from standard input.
 */
@Command(
    name = "create",
    mixinStandardHelpOptions = true,
    description =
        "Create the username/password Account of a User (idempotent, never resets a password).")
public class AccountCreateCommand implements Callable<Integer> {

  @Option(names = "--tenant", required = true, description = "Tenant Slug")
  private String tenant;

  @Option(names = "--user", required = true, description = "User ID (tu_...) from `user create`")
  private String user;

  @Option(names = "--username", required = true, description = "username of the Account")
  private String username;

  @Option(names = "--type", description = "Account type: password (default)")
  private String type = "password";

  @Option(
      names = "--password-stdin",
      description = "Read the initial password as one line from standard input")
  private boolean passwordStdin;

  private final TenantCoreCli root;

  public AccountCreateCommand(TenantCoreCli root) {
    this.root = root;
  }

  @Override
  public Integer call() {
    var formatter = root.formatter();
    if (!"password".equalsIgnoreCase(type)) {
      throw new DomainException(ErrorCode.VALIDATION, "Unsupported Account type '" + type + "'");
    }
    String password = root.passwordReader().read(passwordStdin);
    AccountCreation created =
        root.core()
            .accounts()
            .createPasswordAccount(tenant, user, username, password, root.actor());
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("tenant", tenant);
    fields.put("tenantUserId", user);
    fields.put("username", created.account().username());
    formatter.result(
        created.result() == AccountCreation.Result.CREATED ? "created" : "exists", fields);
    return ExitCodes.OK;
  }
}
