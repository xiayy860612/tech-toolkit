package com.s2u2m.services.core.error;

public interface ErrorCode {
  int getCode();

  String getMessage();

  default ErrorResponseBody getErrorResponse() {
    return new ErrorResponseBody(getCode(), getMessage());
  }
}
