package tech.toolkit.tenantcore.common;

/** A business rule was violated; carries a stable {@link ErrorCode} for the CLI and the API. */
public class DomainException extends RuntimeException {

  private final ErrorCode code;

  public DomainException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
