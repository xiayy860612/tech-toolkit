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
import tech.toolkit.auth.account.LoginAttemptHandler;
import tech.toolkit.auth.account.LoginRateLimitFilter;
import tech.toolkit.auth.account.PasswordChangeRequiredFilter;
import tech.toolkit.auth.account.StatusCheckingAuthenticationProvider;
import tech.toolkit.auth.account.TokenBucketLimiter;
import tech.toolkit.auth.account.User;
import tech.toolkit.auth.account.UserRepository;
import tech.toolkit.auth.config.AuthProperties;

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
      UserRepository users, PasswordEncoder encoder, LoginAttemptHandler attempts) {
    return new StatusCheckingAuthenticationProvider(users, encoder, attempts);
  }

  @Bean
  public TokenBucketLimiter loginLimiter(Clock clock, AuthProperties properties) {
    return new TokenBucketLimiter(
        properties.rateLimit().loginCapacity(), properties.rateLimit().loginRefill(), clock);
  }

  @Bean
  @Order(2)
  public SecurityFilterChain loginFilterChain(
      HttpSecurity http,
      AuthProperties properties,
      Clock clock,
      TokenBucketLimiter loginLimiter,
      UserRepository users)
      throws Exception {
    SavedRequestAwareAuthenticationSuccessHandler continueToSavedRequest =
        new SavedRequestAwareAuthenticationSuccessHandler();
    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        "/login", "/login/expired", "/logged-out", "/error", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            form ->
                form.loginPage("/login")
                    .failureUrl("/login?error")
                    .successHandler(
                        (request, response, authentication) -> {
                          ReauthenticationFilter.onLogin(request.getSession(), clock.instant());
                          boolean mustChange =
                              users
                                  .findById(Long.parseLong(authentication.getName()))
                                  .map(User::mustChangePassword)
                                  .orElse(false);
                          if (mustChange) {
                            // the saved request stays parked until the password has been changed
                            request
                                .getSession()
                                .setAttribute(PasswordChangeRequiredFilter.REQUIRED, true);
                            response.sendRedirect(request.getContextPath() + "/account/password");
                            return;
                          }
                          continueToSavedRequest.onAuthenticationSuccess(
                              request, response, authentication);
                        }))
        .addFilterBefore(
            new LoginRequestExpiryFilter(
                clock, Duration.ofSeconds(properties.login().requestTtlSeconds())),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new LoginRateLimitFilter(loginLimiter), LoginRequestExpiryFilter.class);
    return http.build();
  }
}
