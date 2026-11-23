package tech.toolkit.auth.account;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Loads a user by the typed username. The returned principal is named by the user id, so the OAuth
 * subject, {@code principal_name} and the session principal are all the id (data-model.md). A plain
 * Spring {@code User} is used because the authorization store can only serialize framework types.
 */
public class AuthUserDetailsService implements UserDetailsService {

  private final UserRepository users;

  public AuthUserDetailsService(UserRepository users) {
    this.users = users;
  }

  @Override
  public UserDetails loadUserByUsername(String username) {
    tech.toolkit.auth.account.User user =
        users
            .findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
    // disabled accounts are rejected after the password check
    // (StatusCheckingAuthenticationProvider)
    // so that a disabled account costs the same as a wrong password
    return org.springframework.security.core.userdetails.User.withUsername(
            String.valueOf(user.id()))
        .password(user.passwordHash())
        .authorities(
            user.admin()
                ? List.of(
                    new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER")))
        .build();
  }
}
