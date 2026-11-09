package tech.toolkit.tenantcore.cli;

import tech.toolkit.tenantcore.common.ErrorCode;

/** Process exit codes of the Bootstrap CLI (contracts/cli.md). */
public final class ExitCodes {

  public static final int OK = 0;

  /** Validation or generic error. */
  public static final int ERROR = 1;

  /** A precondition is missing (Tenant, User, Module not found; Login Method not enabled). */
  public static final int PRECONDITION = 2;

  /** The same identifier already exists with different attributes. */
  public static final int CONFLICT = 3;

  private ExitCodes() {}

  public static int of(ErrorCode code) {
    return switch (code) {
      case NOT_FOUND -> PRECONDITION;
      case CONFLICT -> CONFLICT;
      default -> ERROR;
    };
  }
}
