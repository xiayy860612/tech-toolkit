package tech.toolkit.auth.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import tech.toolkit.auth.audit.AuditEvent;
import tech.toolkit.auth.audit.AuditService;
import tech.toolkit.auth.audit.AuditType;

/**
 * Refresh token reuse detection (RFC 9700 section 4.14, FR-011). The framework rotates refresh
 * tokens but keeps only the newest one, so a used token just disappears. Here every replaced token
 * is remembered as a hash. Presenting one again means it leaked: the whole authorization is
 * deleted, which kills the token that replaced it as well. The same table settles a race: of two
 * callers rotating the same token at once only one can record it, the other gets {@code
 * invalid_grant} and no second successor ever exists.
 */
public class ReuseDetectingAuthorizationService implements OAuth2AuthorizationService {

  private final OAuth2AuthorizationService delegate;
  private final JdbcTemplate jdbc;
  private final AuditService audit;

  public ReuseDetectingAuthorizationService(
      OAuth2AuthorizationService delegate, JdbcTemplate jdbc, AuditService audit) {
    this.delegate = delegate;
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    String previous = refreshTokenValue(delegate.findById(authorization.getId()));
    String current = refreshTokenValue(authorization);
    if (previous != null && current != null && !previous.equals(current)) {
      int recorded =
          jdbc.update(
              "insert into auth_used_refresh_token (token_hash, authorization_id) values (?, ?)"
                  + " on conflict do nothing",
              sha256(previous),
              authorization.getId());
      if (recorded == 0) {
        throw new OAuth2AuthenticationException(
            new OAuth2Error(
                OAuth2ErrorCodes.INVALID_GRANT, "The refresh token was already used", null));
      }
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
    OAuth2Authorization found = delegate.findByToken(token, tokenType);
    if (found == null && OAuth2TokenType.REFRESH_TOKEN.equals(tokenType) && token != null) {
      endChainIfReused(token);
    }
    return found;
  }

  private void endChainIfReused(String token) {
    List<String> authorizationIds =
        jdbc.queryForList(
            "select authorization_id from auth_used_refresh_token where token_hash = ?",
            String.class,
            sha256(token));
    for (String id : authorizationIds) {
      OAuth2Authorization chain = delegate.findById(id);
      if (chain != null) {
        delegate.remove(chain);
      }
      audit.record(
          AuditEvent.of(AuditType.REFRESH_REUSE_DETECTED, true)
              .subjectUserId(chain == null ? null : userId(chain.getPrincipalName()))
              .detail(Map.of("chainEnded", chain != null)));
    }
  }

  private static String refreshTokenValue(OAuth2Authorization authorization) {
    if (authorization == null || authorization.getRefreshToken() == null) {
      return null;
    }
    return authorization.getRefreshToken().getToken().getTokenValue();
  }

  private static Long userId(String principalName) {
    try {
      return Long.valueOf(principalName);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }
}
