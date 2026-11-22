package tech.toolkit.auth.audit;

import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/** Turns authentication events into audit records (FR-028). */
@Component
class AuthAuditListener {

  private static final int ACTOR_MAX = 100;

  private final AuditService audit;

  AuthAuditListener(AuditService audit) {
    this.audit = audit;
  }

  @EventListener
  void onFailure(AbstractAuthenticationFailureEvent event) {
    Authentication authentication = event.getAuthentication();
    if (authentication instanceof UsernamePasswordAuthenticationToken) {
      audit.record(
          AuditEvent.of(AuditType.LOGIN_FAILURE, false)
              .actor(truncate(authentication.getName()))
              .sourceIp(remoteAddress(authentication))
              .detail(Map.of("reason", event.getException().getClass().getSimpleName())));
    }
  }

  @EventListener
  void onSuccess(AuthenticationSuccessEvent event) {
    Authentication authentication = event.getAuthentication();
    if (authentication instanceof UsernamePasswordAuthenticationToken) {
      audit.record(
          AuditEvent.of(AuditType.LOGIN_SUCCESS, true)
              .actor(authentication.getName())
              .subjectUserId(userId(authentication.getName()))
              .sourceIp(remoteAddress(authentication)));
    } else if (authentication instanceof OAuth2AccessTokenAuthenticationToken token) {
      audit.record(
          AuditEvent.of(
                  "refresh_token".equals(CurrentRequest.grantType())
                      ? AuditType.TOKEN_REFRESHED
                      : AuditType.TOKEN_ISSUED,
                  true)
              .clientId(token.getRegisteredClient().getClientId()));
    }
  }

  private static String remoteAddress(Authentication authentication) {
    return authentication.getDetails() instanceof WebAuthenticationDetails details
        ? details.getRemoteAddress()
        : null;
  }

  private static String truncate(String value) {
    return value != null && value.length() > ACTOR_MAX ? value.substring(0, ACTOR_MAX) : value;
  }

  private static Long userId(String name) {
    try {
      return Long.valueOf(name);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
