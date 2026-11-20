package tech.toolkit.tenantcore.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;

class ProblemErrorHandlerTest {

  @RestController
  static class Probe {
    @GetMapping("/fail/{code}")
    void fail(@PathVariable ErrorCode code) {
      throw new ApiException(code);
    }

    record Body(@NotBlank @Size(max = 5) String name) {}

    @PostMapping("/validate")
    void validate(@Valid @RequestBody Body body) {}
  }

  private final MockMvc mvc;

  ProblemErrorHandlerTest() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mvc =
        MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new ProblemErrorHandler())
            .setValidator(validator)
            .build();
  }

  static Stream<ErrorCode> codes() {
    return Arrays.stream(ErrorCode.values());
  }

  @ParameterizedTest
  @MethodSource("codes")
  void everyErrorCodeIsRenderedAsProblemJsonWithStableCode(ErrorCode code) throws Exception {
    mvc.perform(get("/fail/" + code.name()))
        .andExpect(status().is(code.status().value()))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(code.name()))
        .andExpect(jsonPath("$.status").value(code.status().value()))
        .andExpect(jsonPath("$.title").isNotEmpty())
        .andExpect(jsonPath("$.type").isNotEmpty());
  }

  @Test
  void containsAllContractCodes() {
    org.assertj.core.api.Assertions.assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name))
        .contains(
            "INVALID_CREDENTIALS",
            "FORBIDDEN",
            "PASSWORD_CHANGE_REQUIRED",
            "USERNAME_TAKEN",
            "VERSION_CONFLICT",
            "LAST_ADMIN",
            "CANNOT_DISABLE_SELF",
            "CANNOT_CHANGE_OWN_ROLE",
            "VALIDATION_FAILED",
            "NOT_FOUND",
            "CANNOT_REVOKE_OWN_SESSIONS",
            "CANNOT_REVOKE_CURRENT_SESSION");
  }

  @Test
  void validationFailuresListTheOffendingFields() throws Exception {
    mvc.perform(
            post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("name"))
        .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
  }

  @Test
  void malformedJsonIsAValidationFailure() throws Exception {
    mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
}
