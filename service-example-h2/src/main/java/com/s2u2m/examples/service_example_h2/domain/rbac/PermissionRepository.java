package com.s2u2m.examples.service_example_h2.domain.rbac;

import com.s2u2m.examples.service_example_h2.domain.user.InnerUser;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
  Set<Permission> findByUid(Long uid);
  Boolean existsByUidAndResourceAndActionAndResourceId(Long uid, Resource resource, String action, String resourceId);
}
