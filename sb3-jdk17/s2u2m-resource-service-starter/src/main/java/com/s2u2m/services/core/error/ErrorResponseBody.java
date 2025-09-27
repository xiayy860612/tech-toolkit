package com.s2u2m.services.core.error;

import lombok.Getter;

@Getter
public class ErrorResponseBody {
  private final int code;
  private final String message;

  public ErrorResponseBody(int code, String message) {
    this.code = code;
    this.message = message;
  }
}
