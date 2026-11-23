package tech.toolkit.auth.oauth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.toolkit.auth.client.ClientAuthorizations;

/**
 * Deletes stored authorizations (codes, access and refresh tokens) so they can no longer be used:
 * on logout, disable, password change, forced sign-out and client deactivation (FR-012).
 */
@Service
public class AuthorizationRevocationService implements ClientAuthorizations {

  private final JdbcTemplate jdbc;

  public AuthorizationRevocationService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Every authorization of one user, across all clients and sessions. */
  public int revokeByPrincipal(String principalName) {
    require(principalName, "principalName");
    return jdbc.update("delete from oauth2_authorization where principal_name = ?", principalName);
  }

  /** Every authorization issued to one client, for all users. */
  @Override
  public int revokeByClientId(String clientId) {
    require(clientId, "clientId");
    return jdbc.update(
        "delete from oauth2_authorization where registered_client_id in"
            + " (select id from oauth2_registered_client where client_id = ?)",
        clientId);
  }

  /** The authorizations that belong to one SSO session (its {@code sid}). */
  public int revokeBySessionId(String sessionId) {
    require(sessionId, "sessionId");
    return jdbc.update("delete from oauth2_authorization where auth_session_id = ?", sessionId);
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
