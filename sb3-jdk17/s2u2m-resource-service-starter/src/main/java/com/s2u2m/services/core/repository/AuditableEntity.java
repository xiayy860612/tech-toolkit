package com.s2u2m.services.core.repository;

import java.time.Instant;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@Getter
public abstract class AuditableEntity {
  @Column(updatable = false)
  @CreatedDate
  private Instant createTime;

  @Column(updatable = false)
  @CreatedBy
  private String createBy;

  @LastModifiedDate
  private Instant updateTime;
  @LastModifiedBy
  private String updateBy;
}
