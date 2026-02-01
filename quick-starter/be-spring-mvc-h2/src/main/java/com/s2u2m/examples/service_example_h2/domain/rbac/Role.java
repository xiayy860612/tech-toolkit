package com.s2u2m.examples.service_example_h2.domain.rbac;

import com.s2u2m.services.core.repository.ImmutableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Entity
@NoArgsConstructor
@AllArgsConstructor
public class Role extends ImmutableEntity {
  public static final String ROLE_PREFIX = "ROLE_";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long uid;

  @Enumerated(EnumType.STRING)
  private RoleName role;

  public Role(Long uid, RoleName role) {
    this.uid = uid;
    this.role = role;
  }

  public GrantedAuthority getAuthority() {
    String authority = ROLE_PREFIX + role.name();
    return new SimpleGrantedAuthority(authority);
  }
}
