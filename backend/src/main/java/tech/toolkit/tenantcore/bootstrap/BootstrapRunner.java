package tech.toolkit.tenantcore.bootstrap;

import java.util.function.IntConsumer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tech.toolkit.tenantcore.platform.ApiException;

/**
 * 非 Web 运行模式：{@code --bootstrap --username=<name> [--display-name=<名>]}。临时密码只输出到标准输出
 * 一次（不经过日志框架），随后退出进程。
 */
@Component
class BootstrapRunner implements ApplicationRunner {

  private final BootstrapService bootstrap;
  private final IntConsumer exit;

  BootstrapRunner(BootstrapService bootstrap, IntConsumer processExit) {
    this.bootstrap = bootstrap;
    this.exit = processExit;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!args.containsOption("bootstrap")) {
      return;
    }
    int code = 0;
    try {
      String username = single(args, "username");
      if (username == null) {
        System.err.println("--username=<name> is required with --bootstrap");
        code = 1;
      } else {
        BootstrapService.Created created =
            bootstrap.createFirstAdmin(username, single(args, "display-name"), null);
        System.out.println(
            "Created tenant admin '" + created.username() + "' in the default tenant.");
        System.out.println(
            "Temporary password (shown once, must be changed at first login): "
                + created.temporaryPassword());
      }
    } catch (ApiException e) {
      System.err.println("Bootstrap failed: " + e.getMessage());
      code = 2;
    }
    exit.accept(code);
  }

  private static String single(ApplicationArguments args, String name) {
    var values = args.getOptionValues(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }
}
