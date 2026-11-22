package tech.toolkit.auth.logout;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.util.StringUtils;
import tech.toolkit.auth.session.SsoSessionIds;

/**
 * Validates an RP-initiated logout request (OIDC RP-Initiated Logout 1.0). The framework's own
 * provider matches {@code sid} against an in-memory session registry, which does not fit our {@code
 * sid} nor several instances; here the {@code id_token_hint} is looked up in the stored
 * authorizations, so it must be one we issued, for a registered client, and the post-logout address
 * must equal a registered one. A hint from another login cannot end this login.
 */
public class LogoutRequestAuthenticator implements AuthenticationProvider {

  private static final OAuth2TokenType ID_TOKEN = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);

  private final RegisteredClientRepository clients;
  private final OAuth2AuthorizationService authorizations;

  public LogoutRequestAuthenticator(
      RegisteredClientRepository clients, OAuth2AuthorizationService authorizations) {
    this.clients = clients;
    this.authorizations = authorizations;
  }

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    OidcLogoutAuthenticationToken request = (OidcLogoutAuthenticationToken) authentication;
    OAuth2Authorization authorization =
        authorizations.findByToken(request.getIdTokenHint(), ID_TOKEN);
    if (authorization == null) {
      throw error(OAuth2ErrorCodes.INVALID_TOKEN, "id_token_hint");
    }
    OAuth2Authorization.Token<OidcIdToken> stored = authorization.getToken(OidcIdToken.class);
    RegisteredClient client = clients.findById(authorization.getRegisteredClientId());
    if (stored == null
        || client == null
        || !stored.getToken().getAudience().contains(client.getClientId())) {
      throw error(OAuth2ErrorCodes.INVALID_TOKEN, "id_token_hint");
    }
    if (StringUtils.hasText(request.getClientId())
        && !request.getClientId().equals(client.getClientId())) {
      throw error(OAuth2ErrorCodes.INVALID_REQUEST, "client_id");
    }
    String postLogout = request.getPostLogoutRedirectUri();
    if (StringUtils.hasText(postLogout)
        && !client.getPostLogoutRedirectUris().contains(postLogout)) {
      throw error(OAuth2ErrorCodes.INVALID_REQUEST, "post_logout_redirect_uri");
    }
    String current = SsoSessionIds.current();
    String hinted = stored.getToken().getClaimAsString("sid");
    if (current != null && !current.equals(hinted)) {
      throw error(OAuth2ErrorCodes.INVALID_REQUEST, "id_token_hint does not belong to this login");
    }
    return new OidcLogoutAuthenticationToken(
        stored.getToken(),
        (Authentication) request.getPrincipal(),
        request.getSessionId(),
        client.getClientId(),
        StringUtils.hasText(postLogout) ? postLogout : null,
        request.getState());
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return OidcLogoutAuthenticationToken.class.isAssignableFrom(authentication);
  }

  private static OAuth2AuthenticationException error(String code, String description) {
    return new OAuth2AuthenticationException(
        new OAuth2Error(code, "OAuth 2.0 Parameter: " + description, null));
  }
}
