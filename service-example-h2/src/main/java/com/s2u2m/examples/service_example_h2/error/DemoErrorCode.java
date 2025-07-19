package com.s2u2m.examples.service_example_h2.error;

import com.s2u2m.services.core.error.ErrorCode;

public enum DemoErrorCode implements ErrorCode {
  UNKNOWN(1, "unknown issues"),
  INVALID_PARAM(2, "invalid parameters"),
  NOT_SUPPORTED_AUTHENTICATION(51, "not supported authentication"),
  DEMO_NOT_EXISTED(101, "demo not existed")
  ;

  private final int code;
  private final String message;

  DemoErrorCode(int code, String message) {
    this.code = code;
    this.message = message;
  }

  @Override
  public int getCode() {
    return code;
  }

  @Override
  public String getMessage() {
    return message;
  }
}
