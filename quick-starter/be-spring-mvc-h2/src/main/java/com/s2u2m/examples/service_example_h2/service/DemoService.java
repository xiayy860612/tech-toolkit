package com.s2u2m.examples.service_example_h2.service;

import com.s2u2m.examples.service_example_h2.repository.DemoEntity;
import com.s2u2m.examples.service_example_h2.repository.DemoEntityRepository;
import com.s2u2m.services.core.log.OperationLog;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
@Transactional(rollbackFor = {Exception.class})
public class DemoService {
  private DemoEntityRepository demoEntityRepository;

  @OperationLog(args = {"#name"})
  public DemoEntity createDemo(String name) {
    DemoEntity entity = new DemoEntity();
    entity.setName(name);
    return demoEntityRepository.save(entity);
  }

  public DemoEntity update(Long id, String name) {
    DemoEntity entity = demoEntityRepository.getReferenceById(id);
    entity.setName(name);
    return demoEntityRepository.save(entity);
  }
}
