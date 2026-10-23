package com.s2u2m.uam.cli;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.application.AccountProvisioningService.Provisioned;
import com.s2u2m.uam.cli.CliArguments.UsageException;
import com.s2u2m.uam.domain.DomainException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code add-account (--user-id <标识> | --account-name-of <已有账号名>) --account-name
 * <新账号名>}：给已有用户追加第二个「账号名 / 密码」账号 （多账号用户只能这样产生）。密码只经标准输入；账号名重复被拒。标准输出只输出新账号的标识。
 */
@Component
@Profile("cli")
public class AddAccountCommand implements CliCommand {

  private final AccountProvisioningService provisioning;

  public AddAccountCommand(AccountProvisioningService provisioning) {
    this.provisioning = provisioning;
  }

  @Override
  public String name() {
    return "add-account";
  }

  @Override
  public int run(List<String> args, InputStream stdin, PrintStream stdout, PrintStream stderr) {
    try {
      CliArguments options =
          CliArguments.parse(args, Set.of("user-id", "account-name-of", "account-name"));
      Optional<String> userId = options.get("user-id");
      Optional<String> accountNameOf = options.get("account-name-of");
      if (userId.isPresent() == accountNameOf.isPresent()) {
        throw new UsageException("--user-id 与 --account-name-of 必须且只能给一个");
      }
      String accountName = options.require("account-name");
      UUID parsedUserId = userId.isPresent() ? parseUserId(userId.get()) : null;
      String password = CliArguments.readPassword(stdin);

      Provisioned created =
          parsedUserId != null
              ? provisioning.addAccount(parsedUserId, accountName, password)
              : provisioning.addAccountToUserOf(accountNameOf.get(), accountName, password);
      stderr.println("已追加账号：" + created.loginName() + "（用户 " + created.userId() + "）");
      stdout.println(created.accountId());
      return 0;
    } catch (UsageException e) {
      stderr.println("参数错误：" + e.getMessage());
      return 2;
    } catch (DomainException e) {
      return CliFailures.report(e, stderr);
    }
  }

  private static UUID parseUserId(String text) {
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException e) {
      throw new UsageException("--user-id 不是合法的 UUID");
    }
  }
}
