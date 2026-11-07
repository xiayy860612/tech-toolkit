package tech.toolkit.tenantcore.cli;

import java.net.InetAddress;
import java.time.Clock;
import java.util.function.Supplier;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.common.DatabaseConfig;

/**
 * Process entry point: configuration comes from environment variables (see backend/.env.example).
 */
public final class TenantCoreCliMain {

  private TenantCoreCliMain() {}

  public static void main(String[] args) {
    Supplier<CoreComponents> core =
        () -> {
          DatabaseConfig config = DatabaseConfig.fromEnv();
          config.migrate();
          return new CoreComponents(config.appDataSource(), Clock.systemUTC());
        };
    int exit =
        TenantCoreCli.commandLine(
                core,
                System.in,
                System.out,
                System.err,
                "cli:" + System.getProperty("user.name") + "@" + hostName(),
                PasswordReader.systemTerminal())
            .execute(args);
    System.exit(exit);
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown-host";
    }
  }
}
