package tech.toolkit.auth.oauth;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tech.toolkit.auth.account.StatusCheckingAuthenticationProvider;
import tech.toolkit.auth.account.UserRepository;
import tech.toolkit.auth.config.AuthProperties;
import tech.toolkit.auth.session.SsoSessionIds;

/**
 * The login page and everything outside the OAuth endpoints (FR-002, FR-007, FR-030): form login
 * with one failure target for every kind of failure, and a fresh {@code sid} for every login.
 */
@Configuration
public class LoginSecurityConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /** Being a bean also switches off Spring Boot's generated default user. */
  @Bean
  public AuthenticationProvider authenticationProvider(
      UserRepository users, PasswordEncoder encoder) {
    return new StatusCheckingAuthenticationProvider(users, encoder);
  }

  @Bean
  @Order(2)
  public SecurityFilterChain loginFilterChain(
      HttpSecurity http, AuthProperties properties, Clock clock) throws Exception {
    SavedRequestAwareAuthenticationSuccessHandler continueToSavedRequest =
        new SavedRequestAwareAuthenticationSuccessHandler();
    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/login", "/login/expired", "/error", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            form ->
                form.loginPage("/login")
                    .failureUrl("/login?error")
                    .successHandler(
                        (request, response, authentication) -> {
                          SsoSessionIds.assign(request.getSession());
                          continueToSavedRequest.onAuthenticationSuccess(
                              request, response, authentication);
                        }))
        .addFilterBefore(
            new LoginRequestExpiryFilter(
                clock, Duration.ofSeconds(properties.login().requestTtlSeconds())),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
