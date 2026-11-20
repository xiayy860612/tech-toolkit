package tech.toolkit.tenantcore.auth;

public enum RevokeReason {
  LOGOUT,
  USER_DISABLED,
  PASSWORD_RESET,
  PASSWORD_CHANGED,
  ADMIN_REVOKED
}
