package tech.toolkit.auth.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A refused admin request, answered as {@code application/problem+json} (RFC 9457). */
public class AdminException extends RuntimeException {

  private final HttpStatus status;
  private final String title;

  public AdminException(HttpStatus status, String title, String detail) {
    super(detail);
    this.status = status;
    this.title = title;
  }

  HttpStatus status() {
    return status;
  }

  String title() {
    return title;
  }
}

/** Turns an {@link AdminException} into its problem response. */
@RestControllerAdvice(basePackageClasses = AdminUserController.class)
class AdminExceptionHandler {

  @ExceptionHandler(AdminException.class)
  ResponseEntity<ProblemDetail> handle(AdminException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
    problem.setTitle(e.title());
    return ResponseEntity.status(e.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
