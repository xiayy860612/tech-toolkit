package tech.toolkit.auth.audit;

/** Kinds of security events recorded in the audit log (data-model.md, auth_audit_event.type). */
public enum AuditType {
  LOGIN_SUCCESS,
  LOGIN_FAILURE,
  ACCOUNT_LOCKED,
  LOGOUT,
  TOKEN_ISSUED,
  TOKEN_REFRESHED,
  TOKEN_REVOKED,
  REFRESH_REUSE_DETECTED,
  USER_CREATED,
  USER_UPDATED,
  PASSWORD_CHANGED,
  CLIENT_CREATED,
  CLIENT_UPDATED,
  SESSION_REVOKED
}
