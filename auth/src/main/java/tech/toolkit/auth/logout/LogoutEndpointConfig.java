package tech.toolkit.auth.logout;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OidcLogoutEndpointConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Plugs auth's own logout handling into the OIDC logout endpoint ({@code /connect/logout}). It is
 * exposed as a {@link Customizer} bean so the OAuth configuration need not depend on this package.
 */
@Configuration
@EnableScheduling
public class LogoutEndpointConfig {

  /**
   * The framework's own provider and default validators check the hint and the post-logout address;
   * only the response is ours: it makes sure the hint belongs to this login, ends it and queues the
   * notices.
   */
  @Bean
  public Customizer<OidcLogoutEndpointConfigurer> logoutEndpointCustomizer(
      LogoutService logoutService) {
    return logout -> logout.logoutResponseHandler(responseHandler(logoutService));
  }

  private static AuthenticationSuccessHandler responseHandler(LogoutService logoutService) {
    SecurityContextLogoutHandler endSession = new SecurityContextLogoutHandler();
    SessionBoundLogoutValidator sameLogin = new SessionBoundLogoutValidator();
    return (request, response, authentication) -> {
      OidcLogoutAuthenticationToken logout = (OidcLogoutAuthenticationToken) authentication;
      try {
        sameLogin.verify(logout);
      } catch (OAuth2AuthenticationException e) {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getError().toString());
        return;
      }
      OidcIdToken idToken = logout.getIdToken();
      logoutService.logout(idToken.getClaimAsString("sid"), idToken.getSubject());
      endSession.logout(request, response, (Authentication) logout.getPrincipal());
      String target = "/logged-out";
      if (logout.getPostLogoutRedirectUri() != null) {
        UriComponentsBuilder uri =
            UriComponentsBuilder.fromUriString(logout.getPostLogoutRedirectUri());
        if (logout.getState() != null) {
          uri.queryParam("state", logout.getState());
        }
        target = uri.build().encode().toUriString();
      }
      response.sendRedirect(target);
    };
  }
}
