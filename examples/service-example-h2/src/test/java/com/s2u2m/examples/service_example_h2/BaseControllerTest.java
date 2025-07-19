package com.s2u2m.examples.service_example_h2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
public class BaseControllerTest {
  @Autowired
  protected MockMvc mvc;
}
