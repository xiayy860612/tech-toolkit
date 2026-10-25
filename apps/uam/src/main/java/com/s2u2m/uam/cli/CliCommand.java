package com.s2u2m.uam.cli;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;

/** 部署期一次性命令（不是运维入口）。标准输出只给命令的结果，人读信息与日志一律写标准错误；返回退出码：0 成功，1 业务失败，2 用法错误。 */
public interface CliCommand {

  /** 命令名，即命令行第一个参数。 */
  String name();

  int run(List<String> args, InputStream stdin, PrintStream stdout, PrintStream stderr);
}
