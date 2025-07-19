package com.s2u2m.examples.service_example_h2.domain.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class UsernameAccount extends Account {
  @Column(unique = true)
  private String username;

  private String password;
}
