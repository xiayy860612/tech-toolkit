package com.s2u2m.uam.infrastructure.config;

import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.policy.PasswordPolicy;
import com.s2u2m.uam.domain.policy.SessionPolicy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把配置（{@code uam.session.*}、{@code uam.login.throttle.*}、{@code uam.password.*}）装配成领域策略，默认值见
 * application.yml。
 */
@Configuration(proxyBeanMethods = false)
public class PolicyConfig {

  @Bean
  SessionPolicy sessionPolicy(
      @Value("${uam.session.idle}") Duration idle,
      @Value("${uam.session.absolute}") Duration absolute,
      @Value("${uam.session.touch-interval}") Duration touchInterval) {
    return new SessionPolicy(idle, absolute, touchInterval);
  }

  @Bean
  LoginThrottlePolicy loginThrottlePolicy(
      @Value("${uam.login.throttle.max-failures}") int maxFailures,
      @Value("${uam.login.throttle.block}") Duration block) {
    return new LoginThrottlePolicy(maxFailures, block);
  }

  @Bean
  PasswordPolicy passwordPolicy(@Value("${uam.password.min-length}") int minLength) {
    return new PasswordPolicy(minLength);
  }
}
