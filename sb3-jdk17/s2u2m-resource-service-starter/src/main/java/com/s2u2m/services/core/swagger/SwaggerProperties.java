package com.s2u2m.services.core.swagger;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "s2u2m.swagger")
public class SwaggerProperties {
  private String title = "APIs";
  private String version = "v1.0.0";
  private String description = "";
}
