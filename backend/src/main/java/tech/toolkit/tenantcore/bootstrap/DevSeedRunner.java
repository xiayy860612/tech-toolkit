package tech.toolkit.tenantcore.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tech.toolkit.tenantcore.platform.ApiException;

/**
 * 仅 {@code dev} profile：在默认租户预置一个本地开发用管理员（凭证为明示的测试值，见 application-dev.yml，
 * 首次登录同样强制改密）。已存在则跳过。绝不用于生产。
 */
@Component
@Profile("dev")
class DevSeedRunner implements ApplicationRunner {

  private final BootstrapService bootstrap;
  private final String username;
  private final String password;

  DevSeedRunner(
      BootstrapService bootstrap,
      @Value("${admin-center.dev-seed.username}") String username,
      @Value("${admin-center.dev-seed.password}") String password) {
    this.bootstrap = bootstrap;
    this.username = username;
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      bootstrap.createFirstAdmin(username, "Dev Admin", password);
    } catch (ApiException alreadyThere) {
      // 已预置，跳过
    }
  }
}
