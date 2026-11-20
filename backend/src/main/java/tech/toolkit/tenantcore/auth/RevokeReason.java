package tech.toolkit.tenantcore.auth;

public enum RevokeReason {
  LOGOUT,
  USER_DISABLED,
  ACCOUNT_DISABLED,
  ACCOUNT_DELETED,
  PASSWORD_RESET,
  PASSWORD_CHANGED,
  ADMIN_REVOKED
}
