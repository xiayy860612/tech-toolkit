package com.s2u2m.examples.service_example_h2.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DemoEntityRepository extends JpaRepository<DemoEntity, Long> {}
