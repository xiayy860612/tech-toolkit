package tech.toolkit.auth.account;

/**
 * What the account code needs from the session and token side when a user's credentials or status
 * change. It is an interface so that {@code account} stays free of a dependency on the OAuth and
 * logout code, which already depend on it; the logout package provides the implementation.
 */
public interface UserSessions {

  /**
   * Ends the user's SSO logins except the one with this session id ({@code null}: all of them),
   * deletes their authorizations and queues the back-channel notices.
   */
  void endLogins(long userId, String exceptSessionId);

  /** Deletes every authorization of the user, so no refresh token survives. */
  void revokeAuthorizations(long userId);
}
