package com.s2u2m.examples.service_example_h2.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.s2u2m.services.core.swagger.SwaggerConfig;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityScheme.Type;

import lombok.AllArgsConstructor;

@Configuration
@AllArgsConstructor
public class DemoSwaggerConfig {

  private static final String BASIC_SCHEME = "basic";

  private final SwaggerConfig swaggerConfig;

  @Bean
  public Components getComponents() {
    return swaggerConfig.getComponents().addSecuritySchemes(BASIC_SCHEME, getBasicScheme());
  }

  @Bean
  public SecurityRequirement getSecurities() {
    return swaggerConfig.getSecurities().addList(BASIC_SCHEME);
  }

  private SecurityScheme getBasicScheme() {
    return new SecurityScheme().scheme(BASIC_SCHEME).type(Type.HTTP);
  }
}
