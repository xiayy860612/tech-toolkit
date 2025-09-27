package com.s2u2m.examples.service_example_h2.controller;

import com.s2u2m.examples.service_example_h2.error.DemoErrorCode;
import com.s2u2m.examples.service_example_h2.repository.DemoEntity;
import com.s2u2m.examples.service_example_h2.repository.DemoEntityRepository;
import com.s2u2m.examples.service_example_h2.service.DemoService;
import com.s2u2m.services.core.error.S2u2mException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demos")
@Validated
@AllArgsConstructor
public class DemoController {
  private final DemoEntityRepository demoEntityRepository;
  private final DemoService demoService;

  @GetMapping
  @PreAuthorize("hasRole('USER')")
  public List<DemoEntity> getDemoList() {
    return demoEntityRepository.findAll();
  }

  record CreateDemoRequest(@NotBlank String name) {}

  @PostMapping
  @PreAuthorize("hasPermission('', 'DEMO', 'create')")
  public DemoEntity createDemo(@Valid @RequestBody CreateDemoRequest request) {
    return demoService.createDemo(request.name);
  }

  record UpdateDemoRequest(@NotBlank String name) {}

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(#id, 'DEMO', 'write')")
  public DemoEntity createDemo(
      @PathVariable Long id, @Valid @RequestBody UpdateDemoRequest request) {
    boolean exists = demoEntityRepository.existsById(id);
    if (!exists) {
      throw new S2u2mException(
          HttpStatus.BAD_REQUEST, DemoErrorCode.DEMO_NOT_EXISTED, "Demo[{0}] is not existed", id);
    }
    return demoService.update(id, request.name);
  }
}
