package com.s2u2m.uam.api;

import com.s2u2m.uam.domain.ErrorCode;
import org.springframework.http.HttpStatus;

/** 原因码到 HTTP 状态的映射，与 {@code contracts/uam/openapi.yaml} 一致。 */
final class ProblemCatalog {

  private ProblemCatalog() {}

  static HttpStatus statusOf(ErrorCode code) {
    return switch (code) {
      case INVALID_CREDENTIALS, SESSION_INVALID, CLIENT_UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
      case CLIENT_FORBIDDEN -> HttpStatus.FORBIDDEN;
      case OPERATOR_REQUIRED, BAD_REQUEST -> HttpStatus.BAD_REQUEST;
      case THROTTLED -> HttpStatus.TOO_MANY_REQUESTS;
      case ACCOUNT_NAME_TAKEN,
          IDEMPOTENCY_IN_PROGRESS,
          CANNOT_DISABLE_SELF,
          CANNOT_DISABLE_LAST_ACTIVE_USER,
          CANNOT_TERMINATE_OWN_SESSION ->
          HttpStatus.CONFLICT;
      // 一个错误码对应一个状态码：validation_failed 一律 422（规则校验与请求体 Bean Validation 失败）；
      // 读不懂的请求（坏 JSON、类型不符）是 400 bad_request
      case VALIDATION_FAILED, CURRENT_PASSWORD_INCORRECT, WEAK_PASSWORD, SAME_AS_CURRENT ->
          HttpStatus.UNPROCESSABLE_ENTITY;
      case SESSION_NOT_FOUND, USER_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
  }
}
