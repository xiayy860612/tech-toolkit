package com.s2u2m.uam.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 命令行参数解析：{@code --名 值} 或 {@code --名=值}；不认识的选项、缺值、多余参数都是用法错误。 */
final class CliArguments {

  /** 用法错误；消息给人看，不含任何输入值。 */
  static final class UsageException extends RuntimeException {
    UsageException(String message) {
      super(message);
    }
  }

  private final Map<String, String> options;

  private CliArguments(Map<String, String> options) {
    this.options = options;
  }

  static CliArguments parse(List<String> args, Set<String> allowed) {
    Map<String, String> options = new HashMap<>();
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (!arg.startsWith("--")) {
        throw new UsageException("多余的参数：" + arg);
      }
      String name = arg.substring(2);
      String value;
      int equals = name.indexOf('=');
      if (equals >= 0) {
        value = name.substring(equals + 1);
        name = name.substring(0, equals);
      } else if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
        value = args.get(++i);
      } else {
        throw new UsageException("选项 --" + name + " 缺少取值");
      }
      if ("password".equals(name)) {
        throw new UsageException("密码只能经标准输入提供，不接受 --password");
      }
      if (!allowed.contains(name)) {
        throw new UsageException("不认识的选项：--" + name);
      }
      options.put(name, value);
    }
    return new CliArguments(options);
  }

  Optional<String> get(String name) {
    return Optional.ofNullable(options.get(name)).filter(value -> !value.isBlank());
  }

  String require(String name) {
    return get(name).orElseThrow(() -> new UsageException("缺少必填选项 --" + name));
  }

  /** 从标准输入读取密码（第一行，只去掉行尾换行）；没有输入是用法错误。 */
  static String readPassword(InputStream stdin) {
    try {
      String line =
          new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8)).readLine();
      if (line == null || line.isEmpty()) {
        throw new UsageException("未从标准输入读到密码（密码只经标准输入提供）");
      }
      return line;
    } catch (IOException e) {
      throw new UsageException("读取标准输入失败");
    }
  }
}
