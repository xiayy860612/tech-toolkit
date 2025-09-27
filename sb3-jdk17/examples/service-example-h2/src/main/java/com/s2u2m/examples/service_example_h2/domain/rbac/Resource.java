package com.s2u2m.examples.service_example_h2.domain.rbac;

import lombok.Getter;

@Getter
public enum Resource {
  DEMO("demos");
  private final String resourceName;

  Resource(String resourceName) {
    this.resourceName = resourceName;
  }
}
