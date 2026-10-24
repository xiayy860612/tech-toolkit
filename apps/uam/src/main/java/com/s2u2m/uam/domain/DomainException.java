package com.s2u2m.uam.domain;

import java.util.List;
import java.util.OptionalLong;

/** 领域 / 用例层的业务异常，带稳定的 {@link ErrorCode}；由 API 层统一映射为 problem+json。 */
public class DomainException extends RuntimeException {

  /** 字段级错误：字段名与原因（如 {@code required}、{@code too_long}、{@code taken}）。 */
  public record FieldError(String field, String reason) {}

  private final ErrorCode errorCode;
  private final List<FieldError> fieldErrors;
  private final Long retryAfterSeconds;

  public DomainException(ErrorCode errorCode, String detail) {
    this(errorCode, detail, List.of(), null);
  }

  private DomainException(
      ErrorCode errorCode, String detail, List<FieldError> fieldErrors, Long retryAfterSeconds) {
    super(detail);
    this.errorCode = errorCode;
    this.fieldErrors = List.copyOf(fieldErrors);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** 校验失败，逐字段给出原因。 */
  public static DomainException validationFailed(List<FieldError> fieldErrors) {
    return new DomainException(ErrorCode.VALIDATION_FAILED, "请求校验失败", fieldErrors, null);
  }

  /** 带字段级原因的业务错误（如 {@code weak_password}）。 */
  public static DomainException withFieldErrors(
      ErrorCode errorCode, String detail, List<FieldError> fieldErrors) {
    return new DomainException(errorCode, detail, fieldErrors, null);
  }

  /** 来源被暂时拒绝；{@code retryAfterSeconds} 为剩余秒数。 */
  public static DomainException throttled(String detail, long retryAfterSeconds) {
    return new DomainException(ErrorCode.THROTTLED, detail, List.of(), retryAfterSeconds);
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  public List<FieldError> fieldErrors() {
    return fieldErrors;
  }

  public OptionalLong retryAfterSeconds() {
    return retryAfterSeconds == null ? OptionalLong.empty() : OptionalLong.of(retryAfterSeconds);
  }
}
