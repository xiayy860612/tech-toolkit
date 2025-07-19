package com.s2u2m.examples.service_example_h2.domain.rbac;

import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {
  Set<Role> findByUid(Long uid);
}

