package com.s2u2m.uam.domain;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** 领域 / 用例层的业务异常，带稳定的 {@link ErrorCode}；由 API 层统一映射为 problem+json。 */
public class DomainException extends RuntimeException {

  /** {@code Problem.reason} 的取值：会话被管理员强制下线。 */
  public static final String REASON_TERMINATED = "terminated";

  /** 字段级错误：字段名与原因（如 {@code required}、{@code too_long}、{@code taken}）。 */
  public record FieldError(String field, String reason) {}

  private final ErrorCode errorCode;
  private final List<FieldError> fieldErrors;
  private final Long retryAfterSeconds;
  private final String reason;

  public DomainException(ErrorCode errorCode, String detail) {
    this(errorCode, detail, List.of(), null, null);
  }

  private DomainException(
      ErrorCode errorCode,
      String detail,
      List<FieldError> fieldErrors,
      Long retryAfterSeconds,
      String reason) {
    super(detail);
    this.errorCode = errorCode;
    this.fieldErrors = List.copyOf(fieldErrors);
    this.retryAfterSeconds = retryAfterSeconds;
    this.reason = reason;
  }

  /** 会话在通知窗口内被管理员强制下线：401 {@code session_invalid}，附可选的 {@code reason}。 */
  public static DomainException sessionTerminated() {
    return new DomainException(
        ErrorCode.SESSION_INVALID, "会话无效或已结束", List.of(), null, REASON_TERMINATED);
  }

  /** 校验失败，逐字段给出原因。 */
  public static DomainException validationFailed(List<FieldError> fieldErrors) {
    return new DomainException(ErrorCode.VALIDATION_FAILED, "请求校验失败", fieldErrors, null, null);
  }

  /** 带字段级原因的业务错误（如 {@code weak_password}）。 */
  public static DomainException withFieldErrors(
      ErrorCode errorCode, String detail, List<FieldError> fieldErrors) {
    return new DomainException(errorCode, detail, fieldErrors, null, null);
  }

  /** 来源被暂时拒绝；{@code retryAfterSeconds} 为剩余秒数。 */
  public static DomainException throttled(String detail, long retryAfterSeconds) {
    return new DomainException(ErrorCode.THROTTLED, detail, List.of(), retryAfterSeconds, null);
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  public List<FieldError> fieldErrors() {
    return fieldErrors;
  }

  /** 可选的机器可读补充原因（契约 {@code Problem.reason}）；目前只有 {@code terminated}。 */
  public Optional<String> reason() {
    return Optional.ofNullable(reason);
  }

  public OptionalLong retryAfterSeconds() {
    return retryAfterSeconds == null ? OptionalLong.empty() : OptionalLong.of(retryAfterSeconds);
  }
}
