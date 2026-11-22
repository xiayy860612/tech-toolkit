package tech.toolkit.auth.oauth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import tech.toolkit.auth.session.SsoClientTracker;
import tech.toolkit.auth.session.SsoSessionIds;

/**
 * Ties every authorization to the SSO login (sid) that created it. The sid is copied from the
 * browser session when the code is issued, so later token and refresh requests, which carry no
 * session, still know it; the {@code auth_session_id} column lets logout and revocation delete
 * exactly one login's authorizations.
 */
public class SessionLinkingAuthorizationService implements OAuth2AuthorizationService {

  /** Attribute name used inside the stored authorization. */
  public static final String SID_ATTRIBUTE = "sid";

  private final OAuth2AuthorizationService delegate;
  private final JdbcTemplate jdbc;
  private final SsoClientTracker tracker;

  public SessionLinkingAuthorizationService(
      OAuth2AuthorizationService delegate, JdbcTemplate jdbc, SsoClientTracker tracker) {
    this.delegate = delegate;
    this.jdbc = jdbc;
    this.tracker = tracker;
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    OAuth2Authorization toSave = authorization;
    String current = SsoSessionIds.current();
    if (current != null && authorization.getAttribute(SID_ATTRIBUTE) == null) {
      toSave = OAuth2Authorization.from(authorization).attribute(SID_ATTRIBUTE, current).build();
    }
    delegate.save(toSave);
    String sid = toSave.getAttribute(SID_ATTRIBUTE);
    if (sid != null) {
      jdbc.update(
          "update oauth2_authorization set auth_session_id = ?"
              + " where id = ? and auth_session_id is distinct from ?",
          sid,
          toSave.getId(),
          sid);
      tracker.track(sid, toSave.getRegisteredClientId());
    }
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
}
