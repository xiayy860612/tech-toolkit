package tech.toolkit.auth.session;

import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tech.toolkit.auth.config.AuthProperties;

/**
 * SSO session limits (FR-003). The idle limit is {@code spring.session.timeout} (30 minutes by
 * default); the absolute limit is enforced here, after the session store has loaded the session and
 * before Spring Security reads the login from it.
 */
@Configuration
public class SessionConfig {

  @Bean
  public FilterRegistrationBean<AbsoluteTimeoutFilter> absoluteTimeoutFilter(
      Clock clock, AuthProperties properties) {
    FilterRegistrationBean<AbsoluteTimeoutFilter> registration =
        new FilterRegistrationBean<>(
            new AbsoluteTimeoutFilter(clock, properties.session().absoluteTimeout()));
    // Spring Session's filter runs at HIGHEST_PRECEDENCE + 50, Spring Security at -100
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
    return registration;
  }
}
