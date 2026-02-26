package com.s2u2m.examples.service_example_h2.security;

import com.s2u2m.examples.service_example_h2.service.auth.UsernameAccountService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
public class BasicSecurityConfig {

  @Bean
  @Order(10)
  public SecurityFilterChain basicFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/auth/login")
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(registry -> registry.anyRequest().authenticated())
        .httpBasic(
            config ->
                config.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
    return http.build();
  }

  @Bean
  public UserDetailsService userDetailsService(UsernameAccountService usernameAccountService) {
    return usernameAccountService;
  }

  @Bean
  public AuthenticationManager basicAuthenticationProvider(
      UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider();
    authenticationProvider.setUserDetailsService(userDetailsService);
    authenticationProvider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(authenticationProvider);
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
}
