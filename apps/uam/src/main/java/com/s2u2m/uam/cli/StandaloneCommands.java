package com.s2u2m.uam.cli;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.OptionalInt;

/**
 * 不需要 Spring 的部署期命令：在启动 Spring 容器<b>之前</b>直接执行，因此不触发数据源、Flyway 与 Redis 的自动配置——镜像里没配数据库也能用 （目前只有
 * {@link GenerateServiceKeyCommand}）。其余命令（{@code init-admin}、{@code add-account}）需要数据库，仍走 {@code
 * cli} profile。
 */
public final class StandaloneCommands {

  private static final List<CliCommand> COMMANDS = List.of(new GenerateServiceKeyCommand());

  private StandaloneCommands() {}

  /** 第一个参数是独立命令时执行并返回退出码；否则返回空，由调用方继续正常启动。 */
  public static OptionalInt tryRun(
      String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr) {
    if (args.length == 0) {
      return OptionalInt.empty();
    }
    return COMMANDS.stream()
        .filter(command -> command.name().equals(args[0]))
        .findFirst()
        .map(
            command ->
                OptionalInt.of(
                    command.run(List.of(args).subList(1, args.length), stdin, stdout, stderr)))
        .orElseGet(OptionalInt::empty);
  }
}
