package com.s2u2m.uam.domain;

import java.util.Arrays;

/** 稳定的机器可读原因码（对应契约 {@code Problem.code}）；界面据此显示文案。 */
public enum ErrorCode {
  INVALID_CREDENTIALS("invalid_credentials"),
  SESSION_INVALID("session_invalid"),
  CLIENT_UNAUTHENTICATED("client_unauthenticated"),
  CLIENT_FORBIDDEN("client_forbidden"),
  OPERATOR_REQUIRED("operator_required"),
  THROTTLED("throttled"),
  ACCOUNT_NAME_TAKEN("account_name_taken"),
  VALIDATION_FAILED("validation_failed"),
  CANNOT_DISABLE_SELF("cannot_disable_self"),
  CANNOT_DISABLE_LAST_ACTIVE_USER("cannot_disable_last_active_user"),
  CANNOT_TERMINATE_OWN_SESSION("cannot_terminate_own_session"),
  SESSION_NOT_FOUND("session_not_found"),
  USER_NOT_FOUND("user_not_found"),
  CURRENT_PASSWORD_INCORRECT("current_password_incorrect"),
  WEAK_PASSWORD("weak_password"),
  SAME_AS_CURRENT("same_as_current"),
  /** 未预期的内部错误；响应体不含任何细节。 */
  INTERNAL_ERROR("internal_error");

  private final String code;

  ErrorCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static ErrorCode fromCode(String code) {
    return Arrays.stream(values())
        .filter(c -> c.code.equals(code))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知的错误码: " + code));
  }
}
