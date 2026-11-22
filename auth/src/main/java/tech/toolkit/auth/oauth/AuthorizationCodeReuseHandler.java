package tech.toolkit.auth.oauth;

import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import tech.toolkit.auth.audit.AuditEvent;
import tech.toolkit.auth.audit.AuditService;
import tech.toolkit.auth.audit.AuditType;

/**
 * RFC 6749 section 4.1.2: when an authorization code is used more than once the server must deny
 * the request and should revoke the tokens issued from it. The framework only invalidates them;
 * here the whole authorization is deleted (FR-001a, spec edge case "code used twice").
 *
 * <p>The framework signals a reuse by saving an authorization whose code <em>and</em> tokens are
 * already invalidated; the first, legitimate exchange only invalidates the code.
 */
public class AuthorizationCodeReuseHandler implements OAuth2AuthorizationService {

  private final OAuth2AuthorizationService delegate;
  private final AuditService audit;

  public AuthorizationCodeReuseHandler(OAuth2AuthorizationService delegate, AuditService audit) {
    this.delegate = delegate;
    this.audit = audit;
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    if (isCodeReuse(authorization)) {
      delegate.remove(authorization);
      audit.record(
          AuditEvent.of(AuditType.TOKEN_REVOKED, true)
              .subjectUserId(parse(authorization.getPrincipalName()))
              .detail(Map.of("reason", "authorization_code_reuse")));
      return;
    }
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
    return delegate.findByToken(token, tokenType);
  }

  static boolean isCodeReuse(OAuth2Authorization authorization) {
    OAuth2Authorization.Token<OAuth2AuthorizationCode> code =
        authorization.getToken(OAuth2AuthorizationCode.class);
    if (code == null || !code.isInvalidated()) {
      return false;
    }
    OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getAccessToken();
    OAuth2Authorization.Token<OAuth2RefreshToken> refresh = authorization.getRefreshToken();
    return (access != null && access.isInvalidated())
        || (refresh != null && refresh.isInvalidated());
  }

  private static Long parse(String principalName) {
    try {
      return Long.valueOf(principalName);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
