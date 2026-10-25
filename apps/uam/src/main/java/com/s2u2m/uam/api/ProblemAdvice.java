package com.s2u2m.uam.api;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 统一错误映射：所有错误都是 RFC 9457 {@code application/problem+json}，带稳定的 {@code code}（FR-035）。
 *
 * <p>响应只含原因码、固定文案与字段名 / 原因，绝不回显请求值；未预期的异常对外不透明，日志只记类型与位置。
 */
@RestControllerAdvice
public class ProblemAdvice extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

  @ExceptionHandler(DomainException.class)
  ResponseEntity<Object> handleDomain(DomainException ex) {
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(ProblemCatalog.statusOf(ex.errorCode()))
            .contentType(MediaType.parseMediaType(Problem.MEDIA_TYPE));
    ex.retryAfterSeconds()
        .ifPresent(seconds -> builder.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds)));
    return builder.body(
        Problem.of(ex.errorCode(), ex.getMessage(), ex.fieldErrors(), ex.reason().orElse(null)));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpected(Exception ex) {
    StackTraceElement[] trace = ex.getStackTrace();
    log.error(
        "uam.unhandled_error type={} at={}",
        ex.getClass().getName(),
        trace.length > 0 ? trace[0] : "-");
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.code(), null, null);
  }

  /** 请求体 Bean Validation 失败：请求读得懂但不符合规则，422 {@code validation_failed}。 */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldError> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new FieldError(fe.getField(), reasonOf(fe)))
            .toList();
    return validationFailed(errors);
  }

  /** 方法参数上的约束（如 {@code @RequestParam @Size}）违反：同为 422 {@code validation_failed}。 */
  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldError> errors = new ArrayList<>();
    for (ParameterValidationResult result : ex.getParameterValidationResults()) {
      if (result instanceof ParameterErrors parameterErrors) {
        parameterErrors
            .getFieldErrors()
            .forEach(fe -> errors.add(new FieldError(fe.getField(), reasonOf(fe))));
      } else {
        String name = result.getMethodParameter().getParameterName();
        result
            .getResolvableErrors()
            .forEach(
                error ->
                    errors.add(new FieldError(name == null ? "parameter" : name, reasonOf(error))));
      }
    }
    return validationFailed(errors);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
    List<FieldError> errors =
        ex.getConstraintViolations().stream()
            .map(
                v ->
                    new FieldError(
                        lastNode(v),
                        reasonOf(
                            v.getConstraintDescriptor()
                                .getAnnotation()
                                .annotationType()
                                .getSimpleName(),
                            v)))
            .toList();
    return validationFailed(errors);
  }

  /** 请求体无法解析（坏 JSON、为空、JSON 类型不对）：400 {@code bad_request}，不回显任何请求内容。 */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.code(), "请求体无法解析（不是合法的 JSON 或类型不符）", null);
  }

  /** Spring MVC 自带的其余错误（404、405、415 …）：同样输出 problem+json，原因码取状态名。 */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    HttpStatus status = HttpStatus.valueOf(statusCode.value());
    String code =
        status == HttpStatus.BAD_REQUEST
            ? ErrorCode.BAD_REQUEST.code()
            : status.name().toLowerCase(java.util.Locale.ROOT);
    ResponseEntity<Object> response = problem(status, code, null, null);
    HttpHeaders merged = new HttpHeaders();
    merged.putAll(headers);
    merged.putAll(response.getHeaders());
    return new ResponseEntity<>(response.getBody(), merged, statusCode);
  }

  private static ResponseEntity<Object> validationFailed(List<FieldError> errors) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_FAILED.code(), "请求校验失败", errors);
  }

  private static ResponseEntity<Object> problem(
      HttpStatus status, String code, String detail, List<FieldError> errors) {
    return ResponseEntity.status(status)
        .contentType(MediaType.parseMediaType(Problem.MEDIA_TYPE))
        .body(Problem.of(status, code, detail, errors));
  }

  private static String reasonOf(MessageSourceResolvable error) {
    String[] codes = error.getCodes();
    String code = codes == null || codes.length == 0 ? "" : codes[codes.length - 1];
    ConstraintViolation<?> violation =
        error instanceof ObjectError objectError && objectError.contains(ConstraintViolation.class)
            ? objectError.unwrap(ConstraintViolation.class)
            : null;
    return reasonOf(code, violation);
  }

  private static String reasonOf(String code, ConstraintViolation<?> violation) {
    return switch (code) {
      case "NotBlank", "NotNull", "NotEmpty" -> "required";
      case "Pattern" -> "invalid_chars";
      case "Size", "Length" -> sizeReason(violation);
      default -> "invalid";
    };
  }

  private static String sizeReason(ConstraintViolation<?> violation) {
    if (violation != null && violation.getInvalidValue() instanceof CharSequence value) {
      Object min = violation.getConstraintDescriptor().getAttributes().get("min");
      if (min instanceof Integer minimum && value.length() < minimum) {
        return "too_short";
      }
    }
    return "too_long";
  }

  private static String lastNode(ConstraintViolation<?> violation) {
    String name = "";
    for (Path.Node node : violation.getPropertyPath()) {
      name = node.getName() == null ? name : node.getName();
    }
    return name;
  }
}
