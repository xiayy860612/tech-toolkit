package tech.toolkit.auth.oauth;

import java.util.Collection;
import java.util.Collections;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;
import tech.toolkit.auth.config.AuthProperties;

/**
 * Shapes the JWTs (FR-008 to FR-010). Access tokens follow RFC 9068: {@code typ: at+jwt}, signed
 * with ES256, {@code client_id}, an audience naming the resource server (this service for now) and
 * the SSO {@code sid}. ID tokens get {@code sid} and {@code azp}. No personal data is added.
 */
@Component
public class TokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

  private final String issuer;

  public TokenCustomizer(AuthProperties properties) {
    this.issuer = properties.issuer();
  }

  @Override
  public void customize(JwtEncodingContext context) {
    OAuth2Authorization authorization = context.getAuthorization();
    String sid =
        authorization == null
            ? null
            : authorization.getAttribute(SessionLinkingAuthorizationService.SID_ATTRIBUTE);
    String clientId = context.getRegisteredClient().getClientId();

    if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
      context.getJwsHeader().type("at+jwt").algorithm(SignatureAlgorithm.ES256);
      // Collections.singletonList, not List.of: the authorization store only deserializes
      // allow-listed types
      context
          .getClaims()
          .audience(Collections.singletonList(issuer))
          .claim("client_id", clientId)
          // RFC 9068 section 2.2.3: scope is a space-delimited string, the framework emits an array
          .claims(
              claims -> {
                if (claims.get("scope") instanceof Collection<?> scopes) {
                  claims.put(
                      "scope", String.join(" ", scopes.stream().map(String::valueOf).toList()));
                }
              });
      if (sid != null) {
        context.getClaims().claim("sid", sid);
      }
    } else if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
      context.getClaims().claim("azp", clientId);
      if (sid != null) {
        context.getClaims().claim("sid", sid);
      }
    }
  }
}
