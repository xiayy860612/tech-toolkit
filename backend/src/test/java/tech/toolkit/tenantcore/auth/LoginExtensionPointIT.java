package tech.toolkit.tenantcore.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;

/**
 * FR-001 / SC-007：用户名密码内置于 User；新增登录方式只需再注册一个 {@link Authenticator}（日后另配 Account 表），不改 {@code
 * app_user}。 多类型并存（SC-004）待第二种类型落地后补测。
 */
@Import(LoginExtensionPointIT.ExtraAuthenticator.class)
class LoginExtensionPointIT extends AbstractPostgresIT {

  /** 仅用来证明：再注册一个 Authenticator 实现（此处委托给密码校验，保证登录行为不变）不需要改动 app_user 或登录流程。 */
  @TestConfiguration
  static class ExtraAuthenticator {
    @Bean
    Authenticator auditedAuthenticator(PasswordAuthenticator delegate) {
      return new Authenticator() {
        @Override
        public LoginType type() {
          return LoginType.USERNAME_PASSWORD;
        }

        @Override
        public boolean verify(AppUser user, String secret) {
          return delegate.verify(user, secret);
        }

        @Override
        public void burnTime(String secret) {
          delegate.burnTime(secret);
        }
      };
    }
  }

  @Autowired List<Authenticator> authenticators;

  @Test
  void aNewLoginMethodIsJustAnotherAuthenticator() {
    assertThat(authenticators).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void everyUserCarriesItsUsernameAndPasswordHashInTheEntity() {
    List<String> fields =
        java.util.Arrays.stream(AppUser.class.getDeclaredFields()).map(Field::getName).toList();
    assertThat(fields).contains("username", "usernameNormalized", "passwordHash");
  }
}
