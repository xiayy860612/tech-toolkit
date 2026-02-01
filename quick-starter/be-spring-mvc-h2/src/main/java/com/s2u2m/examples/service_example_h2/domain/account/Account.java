package com.s2u2m.examples.service_example_h2.domain.account;

import com.s2u2m.examples.service_example_h2.domain.user.InnerUser;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.Setter;

@MappedSuperclass
@Getter
public abstract class Account {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne
  @Setter
  private InnerUser user;
}
