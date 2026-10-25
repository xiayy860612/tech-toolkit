package com.s2u2m.uam.cli;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 部署期命令的入口（{@code cli} profile）：第一个参数是命令名，其余原样交给命令。Spring 启动时已先跑完 Flyway；本 profile 不启动 Web、不连
 * Redis， 日志写标准错误，标准输出留给命令的结果。退出码经 {@link ExitCodeGenerator} 交给启动类。
 */
@Component
@Profile("cli")
public class CliRunner implements ApplicationRunner, ExitCodeGenerator {

  private final List<CliCommand> commands;
  private int exitCode;

  public CliRunner(List<CliCommand> commands) {
    this.commands = commands;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    dispatch(List.of(arguments.getSourceArgs()), System.in, System.out, System.err);
  }

  /** 按命令名分发；未给命令或命令不存在是用法错误（退出码 2）。 */
  public int dispatch(
      List<String> args, InputStream stdin, PrintStream stdout, PrintStream stderr) {
    if (args.isEmpty()) {
      stderr.println(usage());
      return exitCode = 2;
    }
    return commands.stream()
        .filter(command -> command.name().equals(args.get(0)))
        .findFirst()
        .map(command -> exitCode = command.run(args.subList(1, args.size()), stdin, stdout, stderr))
        .orElseGet(
            () -> {
              stderr.println("未知命令：" + args.get(0));
              stderr.println(usage());
              return exitCode = 2;
            });
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  private String usage() {
    return "用法：uam.jar <命令> [选项]；可用命令："
        + String.join("、", commands.stream().map(CliCommand::name).toList());
  }
}
