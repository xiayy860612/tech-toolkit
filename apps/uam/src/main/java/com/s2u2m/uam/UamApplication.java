package com.s2u2m.uam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** UAM 启动类：只委托给 Spring Boot，不含业务逻辑（覆盖率排除路径）。 */
@SpringBootApplication
public class UamApplication {

  public static void main(String[] args) {
    SpringApplication.run(UamApplication.class, args);
  }
}
