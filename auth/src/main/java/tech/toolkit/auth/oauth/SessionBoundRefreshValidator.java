package tech.toolkit.auth.oauth;

import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import tech.toolkit.auth.account.User;
import tech.toolkit.auth.account.UserRepository;
import tech.toolkit.auth.client.ClientExtRepository;
import tech.toolkit.auth.session.SsoSessionActivity;

/**
 * A refresh token only works while what it was issued for is still true (FR-012, FR-014a): the user
 * is enabled and has not changed the password since, the client is not deactivated, and the SSO
 * login it belongs to is alive. When any of that fails the token is treated as unknown, which the
 * framework answers with {@code invalid_grant}. A valid refresh also counts as activity of the
 * login and keeps it from going idle.
 */
public class SessionBoundRefreshValidator implements OAuth2AuthorizationService {

  private final OAuth2AuthorizationService delegate;
  private final UserRepository users;
  private final RegisteredClientRepository clients;
  private final ClientExtRepository clientExtensions;
  private final SsoSessionActivity sessions;

  public SessionBoundRefreshValidator(
      OAuth2AuthorizationService delegate,
      UserRepository users,
      RegisteredClientRepository clients,
      ClientExtRepository clientExtensions,
      SsoSessionActivity sessions) {
    this.delegate = delegate;
    this.users = users;
    this.clients = clients;
    this.clientExtensions = clientExtensions;
    this.sessions = sessions;
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    delegate.save(authorization);
  }

  @Override
  public void remove(OAuth2Authorization authorization) {
    delegate.remove(authorization);
  }

  @Override
  public OAuth2Authorization findById(String id) {
    return delegate.findById(id);
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
    OAuth2Authorization authorization = delegate.findByToken(token, tokenType);
    if (authorization != null
        && OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)
        && !stillValid(authorization)) {
      return null;
    }
    return authorization;
  }

  private boolean stillValid(OAuth2Authorization authorization) {
    User user = userOf(authorization);
    if (user == null || !user.enabled()) {
      return false;
    }
    var refreshToken = authorization.getRefreshToken();
    if (refreshToken == null || refreshToken.getToken().getIssuedAt() == null) {
      return false;
    }
    if (user.passwordChangedAt() != null
        && user.passwordChangedAt().isAfter(refreshToken.getToken().getIssuedAt())) {
      return false;
    }
    RegisteredClient client = clients.findById(authorization.getRegisteredClientId());
    if (client == null
        || !clientExtensions.find(client.getClientId()).map(ext -> ext.enabled()).orElse(true)) {
      return false;
    }
    // last, because it has a side effect: the login is marked as used just now
    return sessions.touchIfAlive(
        authorization.getPrincipalName(),
        authorization.getAttribute(SessionLinkingAuthorizationService.SID_ATTRIBUTE));
  }

  private User userOf(OAuth2Authorization authorization) {
    try {
      return users.findById(Long.parseLong(authorization.getPrincipalName())).orElse(null);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
