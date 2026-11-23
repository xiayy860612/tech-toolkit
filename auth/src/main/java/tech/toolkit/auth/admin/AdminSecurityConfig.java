package tech.toolkit.auth.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * The admin API is a resource server: it takes only bearer access tokens, keeps no session, and
 * lets a request in when the token carries the {@code auth.admin} scope <em>and</em> its user is an
 * enabled administrator at this very moment (FR-019a). No token is a 401, a token that does not
 * qualify is a 403.
 */
@Configuration
public class AdminSecurityConfig {

  private static final String ADMIN_AUTHORITY = "SCOPE_" + AdminScopeCustomizer.ADMIN_SCOPE;

  @Bean
  @Order(0)
  public SecurityFilterChain adminFilterChain(
      HttpSecurity http, JwtDecoder jwtDecoder, AdminScopeCustomizer admins) throws Exception {
    http.securityMatcher("/admin/**")
        .authorizeHttpRequests(authorize -> authorize.anyRequest().access(adminOnly(admins)))
        .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(jwtDecoder)))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable);
    return http.build();
  }

  private static AuthorizationManager<RequestAuthorizationContext> adminOnly(
      AdminScopeCustomizer admins) {
    return (authentication, context) -> {
      var current = authentication.get();
      boolean scoped =
          current != null
              && current.getAuthorities().stream()
                  .anyMatch(a -> ADMIN_AUTHORITY.equals(a.getAuthority()));
      return new AuthorizationDecision(scoped && admins.isEnabledAdmin(current.getName()));
    };
  }
}
