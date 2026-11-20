package tech.toolkit.tenantcore.api;

import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;

/** RFC 9457 problem+json：稳定 {@code code} 供前端映射，校验失败附逐字段说明。 */
@RestControllerAdvice
public class ProblemErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemErrorHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handleApi(ApiException e) {
    return problem(e.code(), e.getMessage(), e.errors());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
    List<ApiException.FieldError> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> new ApiException.FieldError(f.getField(), f.getDefaultMessage()))
            .toList();
    return problem(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.title(), errors);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ProblemDetail> handleUnreadable(Exception e) {
    return problem(ErrorCode.VALIDATION_FAILED, "请求格式不正确", List.of());
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException e) {
    return problem(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.title(), List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
    log.error("Unhandled error", e);
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误");
    pd.setTitle("服务内部错误");
    pd.setProperty("code", "INTERNAL_ERROR");
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  static ResponseEntity<ProblemDetail> problem(
      ErrorCode code, String detail, List<ApiException.FieldError> errors) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
    pd.setType(URI.create("urn:tenant-core:error:" + code.name().toLowerCase().replace('_', '-')));
    pd.setTitle(code.title());
    pd.setProperty("code", code.name());
    if (!errors.isEmpty()) {
      pd.setProperty("errors", errors);
    }
    return ResponseEntity.status(code.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
