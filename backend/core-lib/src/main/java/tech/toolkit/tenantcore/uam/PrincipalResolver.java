package tech.toolkit.tenantcore.uam;

import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.uam.AuthenticatedPrincipal.Source;
import tech.toolkit.tenantcore.uam.InternalJwtService.JwtPrincipal;

/**
 * Identifies the caller from the request headers. The headers decide how: {@code X-Session-Id}
 * carries an Opaque Token (looked up), {@code Authorization: Bearer} carries an Internal JWT
 * (verified locally). Both together, neither, or a credential in the wrong header are rejected.
 */
public class PrincipalResolver {

  private static final String BEARER = "Bearer ";
  private static final String OPAQUE_PREFIX = "tk_";

  private final OpaqueTokenService opaqueTokens;
  private final InternalJwtService jwt;
  private final UserRepository users;

  public PrincipalResolver(
      OpaqueTokenService opaqueTokens, InternalJwtService jwt, UserRepository users) {
    this.opaqueTokens = opaqueTokens;
    this.jwt = jwt;
    this.users = users;
  }

  public AuthenticatedPrincipal resolve(String sessionIdHeader, String authorizationHeader) {
    boolean hasSessionId = sessionIdHeader != null && !sessionIdHeader.isBlank();
    boolean hasAuthorization = authorizationHeader != null && !authorizationHeader.isBlank();
    if (hasSessionId == hasAuthorization) {
      throw unauthorized("Exactly one of X-Session-Id and Authorization must be sent");
    }
    return hasSessionId
        ? fromOpaqueToken(sessionIdHeader.trim())
        : fromBearer(authorizationHeader.trim());
  }

  private AuthenticatedPrincipal fromOpaqueToken(String value) {
    if (!value.startsWith(OPAQUE_PREFIX)) {
      throw unauthorized("Invalid session id");
    }
    OpaqueToken token =
        opaqueTokens.authenticate(value).orElseThrow(() -> unauthorized("Invalid session id"));
    User user =
        users.findById(token.userId()).orElseThrow(() -> unauthorized("Invalid session id"));
    return new AuthenticatedPrincipal(
        Source.OPAQUE, user.tenantUserId(), token.tenantId(), token.sid());
  }

  private AuthenticatedPrincipal fromBearer(String header) {
    if (!header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      throw unauthorized("Authorization must use the Bearer scheme");
    }
    String token = header.substring(BEARER.length()).trim();
    if (token.isEmpty() || token.startsWith(OPAQUE_PREFIX)) {
      throw unauthorized("Authorization must carry an Internal JWT");
    }
    JwtPrincipal principal = jwt.verify(token);
    return new AuthenticatedPrincipal(
        Source.JWT, principal.tenantUserId(), principal.tenantId(), principal.sid());
  }

  private static DomainException unauthorized(String message) {
    return new DomainException(ErrorCode.UNAUTHORIZED, message);
  }
}
