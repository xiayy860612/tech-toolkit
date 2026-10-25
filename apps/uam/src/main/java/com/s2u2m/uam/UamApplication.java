package com.s2u2m.uam;

import com.s2u2m.uam.cli.StandaloneCommands;
import java.util.OptionalInt;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * UAM 启动类：只委托，不含业务逻辑（覆盖率排除路径）。不需要数据库的部署期命令（{@code generate-service-key}）先于 Spring 由 {@link
 * StandaloneCommands} 执行；{@code cli} profile 下把命令的退出码交给进程。
 */
@SpringBootApplication
public class UamApplication {

  public static void main(String[] args) {
    OptionalInt standalone = StandaloneCommands.tryRun(args, System.in, System.out, System.err);
    if (standalone.isPresent()) {
      System.exit(standalone.getAsInt());
    }
    ConfigurableApplicationContext context = SpringApplication.run(UamApplication.class, args);
    if (context.getEnvironment().matchesProfiles("cli")) {
      System.exit(
          SpringApplication.exit(
              context,
              context
                  .getBeansOfType(ExitCodeGenerator.class)
                  .values()
                  .toArray(ExitCodeGenerator[]::new)));
    }
  }
}
