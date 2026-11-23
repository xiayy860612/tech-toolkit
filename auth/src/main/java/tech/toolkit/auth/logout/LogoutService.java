package tech.toolkit.auth.logout;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.auth.audit.AuditEvent;
import tech.toolkit.auth.audit.AuditService;
import tech.toolkit.auth.audit.AuditType;
import tech.toolkit.auth.oauth.AuthorizationRevocationService;
import tech.toolkit.auth.session.SsoClientRepository;

/**
 * Ends one SSO login on the server side (FR-014, FR-014a): queues a back-channel notice for every
 * client the login served, deletes the login's authorizations so no refresh token survives, and
 * audits it. Ending the browser session itself is done by the endpoint's response handler.
 */
@Service
public class LogoutService {

  private final LogoutDeliveryRepository deliveries;
  private final SsoClientRepository ssoClients;
  private final AuthorizationRevocationService revocation;
  private final AuditService audit;

  public LogoutService(
      LogoutDeliveryRepository deliveries,
      SsoClientRepository ssoClients,
      AuthorizationRevocationService revocation,
      AuditService audit) {
    this.deliveries = deliveries;
    this.ssoClients = ssoClients;
    this.revocation = revocation;
    this.audit = audit;
  }

  @Transactional
  public void logout(String sid, String userId) {
    Long subject = parse(userId);
    int notices = 0;
    int revoked = 0;
    if (sid != null) {
      notices = deliveries.enqueueFor(sid);
      revoked = revocation.revokeBySessionId(sid);
      ssoClients.deleteBySession(sid);
    }
    audit.record(AuditEvent.of(AuditType.LOGOUT, true).subjectUserId(subject));
    audit.record(
        AuditEvent.of(AuditType.SESSION_REVOKED, true)
            .subjectUserId(subject)
            .detail(Map.of("revoked", revoked, "noticesQueued", notices)));
  }

  private static Long parse(String userId) {
    try {
      return userId == null ? null : Long.valueOf(userId);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
