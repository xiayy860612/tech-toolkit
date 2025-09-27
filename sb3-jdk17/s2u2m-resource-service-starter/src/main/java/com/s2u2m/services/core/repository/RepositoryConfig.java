package com.s2u2m.services.core.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@Configuration
public class RepositoryConfig {

  @ConditionalOnMissingBean
  @Bean
  public AuditorAware<String> auditorAware() {
    return new EntityAuditAware();
  }
}
