package com.s2u2m.uam.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.ErrorCode;
import java.util.List;
import org.springframework.http.HttpStatus;

/** RFC 9457 problem details（契约 {@code Problem}）。{@code code} 稳定；任何响应都不含密码、哈希、会话标识。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
    String type,
    String title,
    int status,
    String code,
    String detail,
    String reason,
    List<FieldError> errors) {

  public static final String MEDIA_TYPE = "application/problem+json";

  public static Problem of(ErrorCode code, String detail) {
    return of(code, detail, null);
  }

  public static Problem of(ErrorCode code, String detail, List<FieldError> errors) {
    return of(code, detail, errors, null);
  }

  /** {@code reason} 为可选的补充原因，为 {@code null} 时不序列化。 */
  public static Problem of(ErrorCode code, String detail, List<FieldError> errors, String reason) {
    return of(ProblemCatalog.statusOf(code), code.code(), detail, errors, reason);
  }

  public static Problem of(HttpStatus status, String code, String detail, List<FieldError> errors) {
    return of(status, code, detail, errors, null);
  }

  private static Problem of(
      HttpStatus status, String code, String detail, List<FieldError> errors, String reason) {
    return new Problem(
        "about:blank",
        status.getReasonPhrase(),
        status.value(),
        code,
        detail,
        reason,
        errors == null || errors.isEmpty() ? null : errors);
  }
}
