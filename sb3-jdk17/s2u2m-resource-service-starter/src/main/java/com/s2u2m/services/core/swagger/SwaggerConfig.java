package com.s2u2m.services.core.swagger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityScheme.Type;

@Configuration
@EnableConfigurationProperties({SwaggerProperties.class})
public class SwaggerConfig {
  public static final String[] JWT_HOST_URLS =
      new String[] {"/swagger-*/**", "/javainuse-openapi/**", "/v3/api-docs/**", "/v2/api-docs/**"};

  public static final String JWT_SCHEME = "JWT";
  public static final String SCHEME_BEARER = "bearer";

  @ConditionalOnMissingBean
  @Bean
  public OpenAPI openAPI(SwaggerProperties properties) {
    Info info =
        new Info()
            .title(properties.getTitle())
            .description(properties.getDescription())
            .version(properties.getVersion());

    return new OpenAPI().info(info).addSecurityItem(getSecurities()).components(getComponents());
  }

  @ConditionalOnMissingBean
  @Bean
  public Components getComponents() {
    return new Components()
        .addSecuritySchemes(JWT_SCHEME, getJwtScheme());
  }

  @ConditionalOnMissingBean
  @Bean
  public SecurityRequirement getSecurities() {
    return new SecurityRequirement().addList(JWT_SCHEME);
  }

  private SecurityScheme getJwtScheme() {
    return new SecurityScheme()
        .scheme(JWT_SCHEME)
        .type(Type.HTTP)
        .scheme(SCHEME_BEARER)
        .bearerFormat(JWT_SCHEME);
  }
}
