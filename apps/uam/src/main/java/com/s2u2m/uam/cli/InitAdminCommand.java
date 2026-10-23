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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code init-admin --account-name <名> --user-name <名>}：首次部署的一次性初始化，<b>仅在没有任何用户时</b>创建首个用户与「账号名 /
 * 密码」账号，幂等。 密码只经标准输入；创建逻辑全在 {@link AccountProvisioningService}。标准输出只输出新用户的标识。
 */
@Component
@Profile("cli")
public class InitAdminCommand implements CliCommand {

  private final AccountProvisioningService provisioning;

  public InitAdminCommand(AccountProvisioningService provisioning) {
    this.provisioning = provisioning;
  }

  @Override
  public String name() {
    return "init-admin";
  }

  @Override
  public int run(List<String> args, InputStream stdin, PrintStream stdout, PrintStream stderr) {
    try {
      CliArguments options = CliArguments.parse(args, Set.of("account-name", "user-name"));
      String accountName = options.require("account-name");
      String userName = options.require("user-name");
      String password = CliArguments.readPassword(stdin);

      Optional<Provisioned> created =
          provisioning.createInitialUserWithAccount(userName, accountName, password);
      if (created.isEmpty()) {
        stderr.println("已存在用户，跳过：init-admin 只在没有任何用户时创建首个用户");
        return 0;
      }
      stderr.println("已创建初始用户，账号名：" + created.get().loginName());
      stdout.println(created.get().userId());
      return 0;
    } catch (UsageException e) {
      stderr.println("参数错误：" + e.getMessage());
      return 2;
    } catch (DomainException e) {
      return CliFailures.report(e, stderr);
    }
  }
}
