package tech.toolkit.auth.admin;

import java.util.Collection;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.stereotype.Component;
import tech.toolkit.auth.account.User;
import tech.toolkit.auth.account.UserRepository;

/**
 * The {@code auth.admin} scope only ever reaches an administrator (FR-019a). A client may be
 * registered for the scope, and anyone may ask for it, but when the access token is issued the
 * scope is dropped unless the user is an enabled administrator right then. The admin API checks
 * again on every request, so a revoked administrator loses access before the token expires.
 */
@Component
public class AdminScopeCustomizer {

  public static final String ADMIN_SCOPE = "auth.admin";

  private final UserRepository users;

  public AdminScopeCustomizer(UserRepository users) {
    this.users = users;
  }

  /** Removes the admin scope from the token being built when its user is not an administrator. */
  public void restrict(JwtEncodingContext context) {
    String subject = context.getPrincipal().getName();
    context
        .getClaims()
        .claims(
            claims -> {
              if (claims.get("scope") instanceof Collection<?> scopes
                  && scopes.contains(ADMIN_SCOPE)
                  && !isEnabledAdmin(subject)) {
                claims.put("scope", scopes.stream().filter(s -> !ADMIN_SCOPE.equals(s)).toList());
              }
            });
  }

  /** Whether the user exists, is enabled and is an administrator. */
  public boolean isEnabledAdmin(String subject) {
    try {
      return users
          .findById(Long.parseLong(subject))
          .filter(User::enabled)
          .filter(User::admin)
          .isPresent();
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
