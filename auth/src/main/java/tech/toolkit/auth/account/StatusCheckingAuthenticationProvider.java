package tech.toolkit.auth.account;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password authentication with the account status checked <em>after</em> the password was verified.
 * A disabled account therefore does the same hashing work as a wrong password and cannot be told
 * apart by timing or by message (FR-007).
 */
public class StatusCheckingAuthenticationProvider implements AuthenticationProvider {

  private final DaoAuthenticationProvider delegate;
  private final UserRepository users;

  public StatusCheckingAuthenticationProvider(UserRepository users, PasswordEncoder encoder) {
    this.users = users;
    this.delegate = new DaoAuthenticationProvider(new AuthUserDetailsService(users));
    this.delegate.setPasswordEncoder(encoder);
  }

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    Authentication verified = delegate.authenticate(authentication);
    boolean enabled =
        users.findById(Long.parseLong(verified.getName())).map(User::enabled).orElse(false);
    if (!enabled) {
      throw new DisabledException("account is not enabled");
    }
    return verified;
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return delegate.supports(authentication);
  }
}
