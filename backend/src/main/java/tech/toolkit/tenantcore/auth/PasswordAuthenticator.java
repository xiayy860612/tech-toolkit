package tech.toolkit.tenantcore.auth;

import org.springframework.stereotype.Component;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.platform.PasswordHasher;

@Component
public class PasswordAuthenticator implements Authenticator {

  private final PasswordHasher hasher;

  public PasswordAuthenticator(PasswordHasher hasher) {
    this.hasher = hasher;
  }

  @Override
  public LoginType type() {
    return LoginType.USERNAME_PASSWORD;
  }

  @Override
  public boolean verify(AppUser user, String secret) {
    return hasher.matches(secret, user.getPasswordHash());
  }

  @Override
  public void burnTime(String secret) {
    hasher.dummyMatch(secret);
  }
}
