package tech.toolkit.auth.logout;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.toolkit.auth.account.UserSessions;
import tech.toolkit.auth.oauth.AuthorizationRevocationService;
import tech.toolkit.auth.session.SsoSessionIds;

/**
 * Ends a user's logins from the outside (password change, administrator action): each login goes
 * through the normal logout path, so its clients get their back-channel notice and its
 * authorizations are deleted, and then the browser session itself is removed.
 */
@Service
public class UserSessionsService implements UserSessions {

  private final JdbcTemplate jdbc;
  private final LogoutService logout;
  private final AuthorizationRevocationService revocation;

  public UserSessionsService(
      JdbcTemplate jdbc, LogoutService logout, AuthorizationRevocationService revocation) {
    this.jdbc = jdbc;
    this.logout = logout;
    this.revocation = revocation;
  }

  @Override
  public void endLogins(long userId, String exceptSessionId) {
    String principal = String.valueOf(userId);
    List<String> sessionIds =
        jdbc.queryForList(
            "select session_id from spring_session where principal_name = ?",
            String.class,
            principal);
    for (String raw : sessionIds) {
      String sessionId = raw.trim();
      if (sessionId.equals(exceptSessionId)) {
        continue;
      }
      logout.logout(SsoSessionIds.hash(sessionId), principal);
      jdbc.update("delete from spring_session where session_id = ?", raw);
    }
  }

  @Override
  public void revokeAuthorizations(long userId) {
    revocation.revokeByPrincipal(String.valueOf(userId));
  }
}
