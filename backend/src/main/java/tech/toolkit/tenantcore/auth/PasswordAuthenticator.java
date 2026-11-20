package tech.toolkit.tenantcore.auth;

import org.springframework.stereotype.Component;
import tech.toolkit.tenantcore.identity.Account;
import tech.toolkit.tenantcore.identity.AccountType;
import tech.toolkit.tenantcore.identity.PasswordCredentialRepository;
import tech.toolkit.tenantcore.platform.PasswordHasher;

@Component
public class PasswordAuthenticator implements Authenticator {

  private final PasswordCredentialRepository credentials;
  private final PasswordHasher hasher;

  public PasswordAuthenticator(PasswordCredentialRepository credentials, PasswordHasher hasher) {
    this.credentials = credentials;
    this.hasher = hasher;
  }

  @Override
  public AccountType type() {
    return AccountType.USERNAME_PASSWORD;
  }

  @Override
  public boolean verify(Account account, String secret) {
    return credentials
        .findById(account.getId())
        .map(c -> hasher.matches(secret, c.getPasswordHash()))
        .orElseGet(() -> hasher.dummyMatch(secret));
  }

  @Override
  public void burnTime(String secret) {
    hasher.dummyMatch(secret);
  }
}
