package tech.toolkit.auth.account;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
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
  private final LoginAttemptHandler attempts;

  public StatusCheckingAuthenticationProvider(
      UserRepository users, PasswordEncoder encoder, LoginAttemptHandler attempts) {
    this.users = users;
    this.attempts = attempts;
    this.delegate = new DaoAuthenticationProvider(new AuthUserDetailsService(users));
    this.delegate.setPasswordEncoder(encoder);
  }

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    Authentication verified;
    try {
      verified = delegate.authenticate(authentication);
    } catch (BadCredentialsException e) {
      attempts.onFailure(authentication.getName());
      throw e;
    }
    long userId = Long.parseLong(verified.getName());
    boolean enabled = users.findById(userId).map(User::enabled).orElse(false);
    if (!enabled) {
      throw new DisabledException("account is not enabled");
    }
    // a locked account refuses even the right password, and answers like a wrong one (FR-018,
    // FR-007)
    if (attempts.isLocked(userId)) {
      throw new BadCredentialsException("Bad credentials");
    }
    attempts.onSuccess(userId);
    return verified;
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return delegate.supports(authentication);
  }
}
