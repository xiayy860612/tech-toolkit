package com.s2u2m.services.core.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@ConditionalOnMissingBean(annotation = {ControllerAdvice.class})
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(S2u2mException.class)
  public ResponseEntity<ErrorResponseBody> handleException(S2u2mException ex) {
    log.error(ex.getMessage(), ex);
    return ResponseEntity.status(ex.getStatus()).body(ex.getCode().getErrorResponse());
  }

  @ExceptionHandler(AuthorizationDeniedException.class)
  public ResponseEntity<ErrorResponseBody> handleException(AuthorizationDeniedException ex) {
    log.error(ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponseBody> handleException(Exception ex) {
    log.error(ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    log.error(ex.getMessage(), ex);
    return ResponseEntity.badRequest()
        .body(new ErrorResponseBody(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
      HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    log.error(ex.getMessage(), ex);
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
  }
}
