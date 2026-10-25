package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.s2u2m.uam.api.ProblemAdvice;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.ErrorCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** T016：错误体为 RFC 9457 {@code application/problem+json}，带稳定的 {@code code}。 */
class ProblemAdviceTest {

  private static final String PROBLEM_JSON = "application/problem+json";

  /** 契约 {@code Problem.code} 列出的稳定原因码。 */
  private static final List<String> CONTRACT_CODES =
      List.of(
          "invalid_credentials",
          "session_invalid",
          "client_unauthenticated",
          "client_forbidden",
          "operator_required",
          "bad_request",
          "throttled",
          "account_name_taken",
          "validation_failed",
          "cannot_disable_self",
          "cannot_disable_last_active_user",
          "cannot_terminate_own_session",
          "session_not_found",
          "user_not_found",
          "current_password_incorrect",
          "weak_password",
          "same_as_current");

  record Body(
      @NotBlank String accountName,
      @Size(max = 5) String shortName,
      @Size(min = 3) String longName,
      @Pattern(regexp = "[a-z]+") String letters,
      String password) {}

  @RestController
  static class ThrowingController {
    @GetMapping("/domain/{code}")
    String domain(@PathVariable String code) {
      throw new DomainException(ErrorCode.fromCode(code), "原因说明");
    }

    @GetMapping("/fields")
    String fields() {
      throw DomainException.validationFailed(
          List.of(new FieldError("accountName", "taken"), new FieldError("password", "too_weak")));
    }

    @GetMapping("/terminated")
    String terminated() {
      throw DomainException.sessionTerminated();
    }

    @GetMapping("/throttled")
    String throttled() {
      throw DomainException.throttled("请稍后再试", 900);
    }

    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("password=hunter2-secret sessionId=abc123 $argon2id$v=19$x");
    }

    @PostMapping("/body")
    String body(@RequestBody @jakarta.validation.Valid Body body) {
      return "ok";
    }

    @GetMapping("/typed/{id}")
    String typed(@PathVariable java.util.UUID id) {
      return "ok";
    }

    @GetMapping("/paged")
    String paged(@org.springframework.web.bind.annotation.RequestParam int page) {
      return "ok";
    }

    @GetMapping("/violation")
    String violation() {
      var violations =
          jakarta.validation.Validation.buildDefaultValidatorFactory()
              .getValidator()
              .validate(new Body("", "toolong-hunter2", "ab", "A1", "x"));
      throw new jakarta.validation.ConstraintViolationException(violations);
    }

    @GetMapping("/constrained")
    String constrained(
        @org.springframework.web.bind.annotation.RequestParam @Size(max = 3) String q) {
      return "ok";
    }
  }

  private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
  private final MockMvc mvc;

  ProblemAdviceTest() {
    validator.afterPropertiesSet();
    mvc =
        MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ProblemAdvice())
            .setValidator(validator)
            .build();
  }

  static Stream<Arguments> codeToStatus() {
    return Stream.of(
        Arguments.of("invalid_credentials", 401),
        Arguments.of("session_invalid", 401),
        Arguments.of("client_unauthenticated", 401),
        Arguments.of("client_forbidden", 403),
        Arguments.of("operator_required", 400),
        Arguments.of("bad_request", 400),
        Arguments.of("throttled", 429),
        Arguments.of("account_name_taken", 409),
        Arguments.of("validation_failed", 422),
        Arguments.of("cannot_disable_self", 409),
        Arguments.of("cannot_disable_last_active_user", 409),
        Arguments.of("cannot_terminate_own_session", 409),
        Arguments.of("session_not_found", 404),
        Arguments.of("user_not_found", 404),
        Arguments.of("current_password_incorrect", 422),
        Arguments.of("weak_password", 422),
        Arguments.of("same_as_current", 422));
  }

  @Test
  void errorCodeCatalogCoversEveryContractCode() {
    assertThat(Stream.of(ErrorCode.values()).map(ErrorCode::code)).containsAll(CONTRACT_CODES);
    assertThat(ErrorCode.INTERNAL_ERROR.code()).isEqualTo("internal_error");
  }

  @Test
  void unknownCodeIsRejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ErrorCode.fromCode("nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("codeToStatus")
  void domainExceptionBecomesProblemJsonWithStableCode(String code, int expectedStatus)
      throws Exception {
    mvc.perform(get("/domain/" + code))
        .andExpect(status().is(expectedStatus))
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.status").value(expectedStatus))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.title").isString())
        .andExpect(jsonPath("$.detail").value("原因说明"));
  }

  @Test
  void validationFailedCarriesFieldLevelErrors() throws Exception {
    mvc.perform(get("/fields"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("validation_failed"))
        .andExpect(jsonPath("$.errors[0].field").value("accountName"))
        .andExpect(jsonPath("$.errors[0].reason").value("taken"))
        .andExpect(jsonPath("$.errors[1].field").value("password"))
        .andExpect(jsonPath("$.errors[1].reason").value("too_weak"));
  }

  @Test
  void sessionTerminatedCarriesTheOptionalReasonOnA401SessionInvalid() throws Exception {
    mvc.perform(get("/terminated"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("session_invalid"))
        .andExpect(jsonPath("$.reason").value("terminated"))
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  @Test
  void problemsWithoutAReasonOmitTheReasonMember() throws Exception {
    mvc.perform(get("/domain/session_invalid")).andExpect(jsonPath("$.reason").doesNotExist());
    mvc.perform(get("/boom")).andExpect(jsonPath("$.reason").doesNotExist());
  }

  @Test
  void problemsWithoutFieldErrorsOmitTheErrorsMember() throws Exception {
    mvc.perform(get("/domain/session_invalid")).andExpect(jsonPath("$.errors").doesNotExist());
  }

  @Test
  void throttledCarriesRetryAfter() throws Exception {
    mvc.perform(get("/throttled"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "900"))
        .andExpect(jsonPath("$.code").value("throttled"));
  }

  @Test
  void beanValidationFailuresAreMappedToFieldErrorsWithoutEchoingValues() throws Exception {
    String secret = "hunter2-secret-pw";
    String json =
        """
        {"accountName":"","shortName":"toolong","longName":"ab","letters":"A1","password":"%s"}
        """
            .formatted(secret);
    String response =
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(reason("accountName", "required"))
            .andExpect(reason("shortName", "too_long"))
            .andExpect(reason("longName", "too_short"))
            .andExpect(reason("letters", "invalid_chars"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(response).doesNotContain(secret);
  }

  private static ResultMatcher reason(String field, String reason) {
    return jsonPath("$.errors[?(@.field=='" + field + "')].reason", contains(reason));
  }

  @Test
  void unparseableBodiesAreBadRequestsWithoutEchoingTheInput() throws Exception {
    for (String json : List.of("{not json hunter2-secret", "", "{\"accountName\":{\"x\":1}}")) {
      String response =
          mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content(json))
              .andExpect(status().isBadRequest())
              .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
              .andExpect(jsonPath("$.status").value(400))
              .andExpect(jsonPath("$.code").value("bad_request"))
              .andExpect(jsonPath("$.errors").doesNotExist())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(response).doesNotContain("hunter2");
    }
  }

  @Test
  void typeMismatchesAreBadRequestsWithoutEchoingTheValue() throws Exception {
    String pathResponse =
        mvc.perform(get("/typed/not-a-uuid-hunter2"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("bad_request"))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(pathResponse).doesNotContain("hunter2");

    mvc.perform(get("/paged").param("page", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("bad_request"));
  }

  @Test
  void constraintViolationExceptionsAre422WithFieldReasons() throws Exception {
    String response =
        mvc.perform(get("/violation"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(reason("accountName", "required"))
            .andExpect(reason("shortName", "too_long"))
            .andExpect(reason("longName", "too_short"))
            .andExpect(reason("letters", "invalid_chars"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain("hunter2");
  }

  @Test
  void aMissingRequiredParameterIsABadRequest() throws Exception {
    mvc.perform(get("/paged"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("bad_request"));
  }

  @Test
  void constraintViolationsOnParametersAre422ValidationFailed() throws Exception {
    String response =
        mvc.perform(get("/constrained").param("q", "toolong-hunter2"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(reason("q", "too_long"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain("hunter2");
  }

  @Test
  void springMvcErrorResponsesAreProblemJsonToo() throws Exception {
    mvc.perform(post("/boom"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("method_not_allowed"));
  }

  @Test
  void unexpectedErrorsAreOpaqueAndNeverLeakSecrets() throws Exception {
    String response =
        mvc.perform(get("/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("internal_error"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response)
        .doesNotContain("hunter2")
        .doesNotContain("sessionId")
        .doesNotContain("abc123")
        .doesNotContain("argon2");
  }
}
