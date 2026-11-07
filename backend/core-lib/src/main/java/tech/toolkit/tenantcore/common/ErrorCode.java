package tech.toolkit.tenantcore.common;

/** Business error codes shared by the CLI and the API (see contracts/tenant-core-api.yaml). */
public enum ErrorCode {
  INVALID_CREDENTIALS,
  MODULE_NOT_SUBSCRIBED,
  UNAUTHORIZED,
  PASSWORD_CHANGE_REQUIRED,
  PASSWORD_POLICY,
  VALIDATION,
  NOT_FOUND,
  CONFLICT
}
