package com.s2u2m.services.core.repository;

import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class EntityAuditAware implements AuditorAware<String> {
  @Override
  public Optional<String> getCurrentAuditor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String uid = Optional.ofNullable(authentication).map(Authentication::getName).orElse("");
    return Optional.of(uid);
  }
}
