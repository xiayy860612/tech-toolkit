package com.s2u2m.uam;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** UAM 启动类：只委托给 Spring Boot，不含业务逻辑（覆盖率排除路径）。{@code cli} profile 下把命令的退出码交给进程。 */
@SpringBootApplication
public class UamApplication {

  public static void main(String[] args) {
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
