package com.s2u2m.examples.service_example_h2.domain.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UsernameAccountRepository extends JpaRepository<UsernameAccount, Long> {
  UsernameAccount findByUsername(String username);
}
