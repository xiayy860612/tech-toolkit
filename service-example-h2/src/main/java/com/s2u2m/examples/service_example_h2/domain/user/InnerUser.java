package com.s2u2m.examples.service_example_h2.domain.user;

import com.s2u2m.services.core.repository.AuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
public class InnerUser extends AuditableEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Setter private String name;
  @Setter private String avatar;

  public UserInfo getUserInfo() {
    return UserInfo.builder().name(name).avatar(avatar).build();
  }
}
