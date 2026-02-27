package com.s2u2m.examples.service_example_h2.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<InnerUser, Long> {}
