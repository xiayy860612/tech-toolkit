package tech.toolkit.tenantcore.cli;

import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;
import tech.toolkit.tenantcore.CoreComponents;
import tech.toolkit.tenantcore.cli.commands.AccountCreateCommand;
import tech.toolkit.tenantcore.cli.commands.LoginMethodEnableCommand;
import tech.toolkit.tenantcore.cli.commands.SubscriptionAddCommand;
import tech.toolkit.tenantcore.cli.commands.TenantCreateCommand;
import tech.toolkit.tenantcore.cli.commands.TenantShowCommand;
import tech.toolkit.tenantcore.cli.commands.UserCreateCommand;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;

/**
 * Root of the Bootstrap CLI. Holds what every command needs (services, streams, operator identity)
 * so tests can run the whole CLI in-process against a real database.
 */
@Command(
    name = "tenant-core-cli",
    mixinStandardHelpOptions = true,
    version = "tenant-core-cli 0.1.0",
    description =
        "Bootstrap CLI for Tenant Core: create Tenant, enable Login Method, create User"
            + " and Account, subscribe Module.")
public class TenantCoreCli implements Runnable {

  @Option(
      names = "--output",
      scope = ScopeType.INHERIT,
      description = "Output format: text (default) or json")
  private String output = "text";

  @Spec private CommandSpec spec;

  private final Supplier<CoreComponents> coreSupplier;
  private final InputStream stdin;
  private final PrintStream out;
  private final String actor;
  private final PasswordReader.Terminal terminal;
  private CoreComponents core;

  TenantCoreCli(
      Supplier<CoreComponents> coreSupplier,
      InputStream stdin,
      PrintStream out,
      String actor,
      PasswordReader.Terminal terminal) {
    this.coreSupplier = coreSupplier;
    this.stdin = stdin;
    this.out = out;
    this.actor = actor;
    this.terminal = terminal;
  }

  /** Builds the full command tree. {@code terminal} may be null when no console is available. */
  public static CommandLine commandLine(
      Supplier<CoreComponents> core,
      InputStream stdin,
      PrintStream out,
      PrintStream err,
      String actor,
      PasswordReader.Terminal terminal) {
    TenantCoreCli root = new TenantCoreCli(core, stdin, out, actor, terminal);
    CommandLine cli = new CommandLine(root);
    cli.addSubcommand(
        "tenant",
        group(
            "create", new TenantCreateCommand(root),
            "show", new TenantShowCommand(root)));
    cli.addSubcommand("login-method", group("enable", new LoginMethodEnableCommand(root)));
    cli.addSubcommand("user", group("create", new UserCreateCommand(root)));
    cli.addSubcommand("account", group("create", new AccountCreateCommand(root)));
    cli.addSubcommand("subscription", group("add", new SubscriptionAddCommand(root)));
    cli.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
    cli.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
    useExitCodes(cli);
    // Never echo unknown arguments: a mistyped `--password <value>` would print the secret.
    cli.setParameterExceptionHandler(
        (ex, args) -> {
          if (ex instanceof CommandLine.UnmatchedArgumentException) {
            err.println(
                "Error: Unknown option or unexpected argument (arguments are not echoed)."
                    + " Passwords are never accepted as arguments: use --password-stdin or the"
                    + " hidden prompt. Run with --help for usage.");
          } else {
            err.println("Error: " + ex.getMessage());
          }
          return ExitCodes.ERROR;
        });
    cli.setExecutionExceptionHandler(
        (ex, commandLine, parseResult) -> {
          err.println("Error: " + ex.getMessage());
          return ex instanceof DomainException domain
              ? ExitCodes.of(domain.code())
              : ExitCodes.ERROR;
        });
    return cli;
  }

  /** picocli uses the failing subcommand's own settings, so apply ours to the whole tree. */
  private static void useExitCodes(CommandLine cli) {
    cli.getCommandSpec().exitCodeOnInvalidInput(ExitCodes.ERROR);
    cli.getCommandSpec().exitCodeOnExecutionException(ExitCodes.ERROR);
    cli.getSubcommands().values().forEach(TenantCoreCli::useExitCodes);
  }

  private static CommandLine group(Object... nameAndCommand) {
    CommandLine group = new CommandLine(new Group());
    for (int i = 0; i < nameAndCommand.length; i += 2) {
      group.addSubcommand((String) nameAndCommand[i], nameAndCommand[i + 1]);
    }
    return group;
  }

  @Override
  public void run() {
    throw new ParameterException(spec.commandLine(), "Missing command");
  }

  /** A command group such as {@code tenant}; it only dispatches to its subcommands. */
  @Command(mixinStandardHelpOptions = true)
  static class Group implements Runnable {
    @Spec private CommandSpec spec;

    @Override
    public void run() {
      throw new ParameterException(spec.commandLine(), "Missing command");
    }
  }

  public CoreComponents core() {
    if (core == null) {
      core = coreSupplier.get();
    }
    return core;
  }

  public String actor() {
    return actor;
  }

  public OutputFormatter formatter() {
    if (!"text".equals(output) && !"json".equals(output)) {
      throw new DomainException(ErrorCode.VALIDATION, "--output must be text or json");
    }
    return new OutputFormatter(out, "json".equals(output));
  }

  public PasswordReader passwordReader() {
    return new PasswordReader(terminal, stdin);
  }
}
