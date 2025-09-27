package com.s2u2m.services.core.error;

import java.text.MessageFormat;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public class S2u2mException extends RuntimeException {

  private final HttpStatus status;
  private final ErrorCode code;

  public S2u2mException(HttpStatus status, ErrorCode code, Throwable cause, String message) {
    super(message, cause);
    this.status = status;
    this.code = code;
  }

  public S2u2mException(
      HttpStatus status, ErrorCode code, Throwable cause, String pattern, Object... args) {
    this(status, code, cause, MessageFormat.format(pattern, args));
  }

  public S2u2mException(
      HttpStatus status, ErrorCode code, String pattern, Object... args) {
    this(status, code, null, pattern, args);
  }

  public S2u2mException(ErrorCode code, Throwable cause, String pattern, Object... args) {
    this(HttpStatus.INTERNAL_SERVER_ERROR, code, cause, MessageFormat.format(pattern, args));
  }

  public S2u2mException(ErrorCode code, String pattern, Object... args) {
    this(code, null, pattern, args);
  }
}
