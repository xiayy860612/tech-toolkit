package tech.toolkit.tenantcore.api;

import java.util.List;

public class ApiException extends RuntimeException {

  public record FieldError(String field, String message) {}

  private final ErrorCode code;
  private final List<FieldError> errors;

  public ApiException(ErrorCode code) {
    this(code, code.title(), List.of());
  }

  public ApiException(ErrorCode code, String detail) {
    this(code, detail, List.of());
  }

  public ApiException(ErrorCode code, String detail, List<FieldError> errors) {
    super(detail);
    this.code = code;
    this.errors = errors;
  }

  public ErrorCode code() {
    return code;
  }

  public List<FieldError> errors() {
    return errors;
  }
}
