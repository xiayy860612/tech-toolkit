package com.s2u2m.examples.service_example_h2.domain.rbac;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.NotImplementedException;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class DemoPermissionEvaluator implements PermissionEvaluator {

  private final PermissionRepository permissionRepository;

  @Override
  public boolean hasPermission(
      Authentication authentication, Object targetDomainObject, Object permission) {
    throw new NotImplementedException();
  }

  @Override
  public boolean hasPermission(
      Authentication authentication, Serializable targetId, String targetType, Object permission) {
    Long uid = Long.valueOf(authentication.getName());
    return permissionRepository.existsByUidAndResourceAndActionAndResourceId(
        uid, Resource.valueOf(targetType), permission.toString(), targetId.toString());
  }
}
