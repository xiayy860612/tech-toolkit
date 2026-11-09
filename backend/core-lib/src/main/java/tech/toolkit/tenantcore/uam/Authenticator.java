package tech.toolkit.tenantcore.uam;

import tech.toolkit.tenantcore.tenant.Tenant;

/**
 * Turns a login request into a verified identity. Login Methods plug in here (only password in the
 * MVP); everything after this point — tokens, gateway, services — does not care how the User logged
 * in.
 */
public interface Authenticator {

  AuthenticatedIdentity authenticate(LoginRequest request);

  /** What the User typed on the login page. */
  record LoginRequest(String tenantSlug, String username, String password) {
    @Override
    public String toString() {
      return "LoginRequest[tenantSlug=" + tenantSlug + ", username=" + username + "]";
    }
  }

  /** The verified result: which Tenant, User and Account, and whether the password must change. */
  record AuthenticatedIdentity(
      Tenant tenant, User user, Account account, boolean mustChangePassword) {}
}
