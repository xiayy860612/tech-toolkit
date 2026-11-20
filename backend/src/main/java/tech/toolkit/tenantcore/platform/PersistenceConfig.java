package tech.toolkit.tenantcore.platform;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PersistenceConfig {

  /** 无租户上下文时使用永不匹配的标识，配合 RLS 保证读不到也写不进任何行。 */
  static final long NO_TENANT = -1L;

  @Bean
  PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
    return new TenantScopedTransactionManager(emf);
  }

  @Bean
  CurrentTenantIdentifierResolver<Long> tenantIdentifierResolver() {
    return new CurrentTenantIdentifierResolver<>() {
      @Override
      public Long resolveCurrentTenantIdentifier() {
        return TenantContext.current().orElse(NO_TENANT);
      }

      @Override
      public boolean validateExistingCurrentSessions() {
        return false;
      }
    };
  }

  @Bean
  HibernatePropertiesCustomizer tenantResolverCustomizer(
      CurrentTenantIdentifierResolver<Long> resolver) {
    return props -> props.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
