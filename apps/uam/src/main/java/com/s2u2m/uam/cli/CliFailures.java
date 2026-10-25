package com.s2u2m.uam.cli;

import com.s2u2m.uam.domain.DomainException;
import java.io.PrintStream;
import java.util.stream.Collectors;

/** 命令的业务失败输出：原因码与字段名 / 原因，绝不回显输入值（密码等）。 */
final class CliFailures {

  private CliFailures() {}

  /** 打印失败原因，返回退出码 1。 */
  static int report(DomainException e, PrintStream stderr) {
    String fields =
        e.fieldErrors().stream()
            .map(error -> error.field() + ": " + error.reason())
            .collect(Collectors.joining("；"));
    stderr.println("失败：" + e.errorCode().code() + (fields.isEmpty() ? "" : "（" + fields + "）"));
    return 1;
  }
}
