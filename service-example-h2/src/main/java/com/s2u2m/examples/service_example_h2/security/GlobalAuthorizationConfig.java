package com.s2u2m.examples.service_example_h2.security;

import com.s2u2m.examples.service_example_h2.domain.rbac.DemoPermissionEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;

@Configuration
@RequiredArgsConstructor
public class GlobalAuthorizationConfig {
  private final DemoPermissionEvaluator permissionEvaluator;

  @Bean
  public MethodSecurityExpressionHandler expressionHandler() {
    var handler = new DefaultMethodSecurityExpressionHandler();
    handler.setPermissionEvaluator(permissionEvaluator);
    return handler;
  }
}
