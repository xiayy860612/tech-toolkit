package com.s2u2m.examples.service_example_h2.security;

import com.s2u2m.services.core.security.ResourceServiceHttpSecurityCustomizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

public class GlobalHttpSecurityCustomizer extends ResourceServiceHttpSecurityCustomizer {

  @Override
  protected void configProjectPermittedRequests(
      AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
          registry) {
    registry
      .requestMatchers("/h2-console", "/h2-console/**").permitAll()
//      .requestMatchers("/error").permitAll()
    ;
  }
}
