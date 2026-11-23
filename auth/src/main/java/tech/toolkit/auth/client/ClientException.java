package tech.toolkit.auth.client;

/** A client operation that cannot be done, with the reason in terms the caller can map. */
public class ClientException extends RuntimeException {

  /** Why the operation was refused. */
  public enum Kind {
    INVALID,
    NOT_FOUND,
    CONFLICT
  }

  private final Kind kind;

  public ClientException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
