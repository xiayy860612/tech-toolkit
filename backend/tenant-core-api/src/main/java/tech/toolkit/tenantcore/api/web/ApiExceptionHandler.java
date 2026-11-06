package tech.toolkit.tenantcore.api.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;

/**
 * Maps business errors to the status codes and {@code {code,message}} bodies of the API contract.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  public record ErrorBody(String code, String message) {}

  static HttpStatus statusOf(ErrorCode code) {
    return switch (code) {
      case INVALID_CREDENTIALS, UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
      case MODULE_NOT_SUBSCRIBED, PASSWORD_CHANGE_REQUIRED -> HttpStatus.FORBIDDEN;
      case PASSWORD_POLICY, VALIDATION -> HttpStatus.BAD_REQUEST;
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case CONFLICT -> HttpStatus.CONFLICT;
    };
  }

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ErrorBody> domain(DomainException e) {
    return ResponseEntity.status(statusOf(e.code()))
        .body(new ErrorBody(e.code().name(), e.getMessage()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ErrorBody> badRequest(Exception e) {
    return ResponseEntity.badRequest()
        .body(new ErrorBody(ErrorCode.VALIDATION.name(), "The request is malformed"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorBody> notFound(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody(ErrorCode.NOT_FOUND.name(), "Not found"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorBody> unexpected(Exception e) {
    LOG.error("Unexpected error", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ErrorBody("INTERNAL_ERROR", "Unexpected error"));
  }
}
