package tech.toolkit.auth.logout;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.util.StringUtils;
import tech.toolkit.auth.session.SsoSessionIds;

/**
 * Binds a logout request to the login it comes from. The framework compares the ID token's {@code
 * sid} with its in-memory session registry, which only knows the sessions of this very instance;
 * this check uses the session id the request carries instead, so it holds on every instance. A hint
 * issued to another login can therefore never end this one.
 */
public final class SessionBoundLogoutValidator {

  /** Throws when the request has a login but the ID token belongs to another one. */
  public void verify(OidcLogoutAuthenticationToken logout) {
    String sessionId = logout.getSessionId();
    if (!StringUtils.hasText(sessionId)) {
      return; // no login to bind to: the session is already gone, the hint alone identifies it
    }
    String sid = logout.getIdToken().getClaimAsString("sid");
    if (sid == null || !SsoSessionIds.hash(sessionId).equals(sid)) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error(
              OAuth2ErrorCodes.INVALID_REQUEST,
              "OpenID Connect 1.0 Logout Request Parameter: sid",
              "https://openid.net/specs/openid-connect-rpinitiated-1_0.html#ValidationAndErrorHandling"));
    }
  }
}
