package com.s2u2m.services.core;

import com.s2u2m.services.core.error.GlobalExceptionHandler;
import com.s2u2m.services.core.repository.RepositoryConfig;
import com.s2u2m.services.core.security.GlobalSecurityConfig;
import com.s2u2m.services.core.swagger.SwaggerConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
  GlobalExceptionHandler.class,
  SwaggerConfig.class,
  GlobalSecurityConfig.class,
  RepositoryConfig.class
})
public class S2u2mServiceFrameworkAutoConfigure {}
