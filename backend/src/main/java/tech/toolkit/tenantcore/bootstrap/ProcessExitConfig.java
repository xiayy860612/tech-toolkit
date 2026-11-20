package tech.toolkit.tenantcore.bootstrap;

import java.util.function.IntConsumer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 进程退出动作（先优雅关闭 Spring 上下文再 System.exit），抽成 Bean 以便测试替换。 */
@Configuration
class ProcessExitConfig {

  @Bean
  IntConsumer processExit(ConfigurableApplicationContext context) {
    return code -> System.exit(SpringApplication.exit(context, () -> code));
  }
}
