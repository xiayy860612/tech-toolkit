package com.s2u2m.examples.service_example_h2.domain.rbac;

import com.s2u2m.examples.service_example_h2.domain.user.InnerUser;
import com.s2u2m.services.core.repository.ImmutableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.util.StringUtils;

@Entity
@NoArgsConstructor
@Getter
@EqualsAndHashCode(of = {"resource", "resourceId", "action"})
public class Permission extends ImmutableEntity {
  public static final String PERMISSION_PREFIX = "PERMISSION_";
  public static final String PERMISSION_DELIMITER = ":";
  public static final String ALL_RESOURCES = "*";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long uid;

  @Enumerated(EnumType.STRING)
  private Resource resource;

  private String resourceId;
  private String action;

  public Permission(Resource resource, String resourceId, String action) {
    this.resource = resource;
    this.resourceId = StringUtils.hasLength(resourceId) ? resourceId : ALL_RESOURCES;
    this.action = action;
  }

  public Permission(Resource resource, Long resourceId, String action) {
    this.resource = resource;
    this.resourceId = Objects.nonNull(resourceId) ? resourceId.toString() : ALL_RESOURCES;
    this.action = action;
  }

  public GrantedAuthority getAuthority() {
    String authority =
        PERMISSION_PREFIX
            + String.join(PERMISSION_DELIMITER, resource.getResourceName(), resourceId, action);
    return new SimpleGrantedAuthority(authority);
  }
}
