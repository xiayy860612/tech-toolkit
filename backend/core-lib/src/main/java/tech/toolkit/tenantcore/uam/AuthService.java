package tech.toolkit.tenantcore.uam;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.audit.AuditRecorder;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.tenant.Tenant;
import tech.toolkit.tenantcore.tenant.TenantLookup;
import tech.toolkit.tenantcore.uam.Authenticator.AuthenticatedIdentity;
import tech.toolkit.tenantcore.uam.Authenticator.LoginRequest;
import tech.toolkit.tenantcore.uam.InternalJwtService.IssuedJwt;
import tech.toolkit.tenantcore.uam.OpaqueTokenService.IssuedToken;

/** Login, logout, change password, token exchange and "who am I" for the API. */
public class AuthService {

  /** A new Session: the Opaque Token (session id) and when it ends at the latest. */
  public record LoginResult(
      String token, Instant expiresAt, boolean mustChangePassword, String tenantUserId) {
    @Override
    public String toString() {
      return "LoginResult[expiresAt="
          + expiresAt
          + ", mustChangePassword="
          + mustChangePassword
          + "]";
    }
  }

  public record ExchangeResult(String jwt, Instant expiresAt) {}

  public record Me(
      String tenantUserId,
      String displayName,
      String accountUsername,
      String tenantSlug,
      boolean mustChangePassword) {}

  private final Authenticator authenticator;
  private final OpaqueTokenService tokens;
  private final InternalJwtService jwt;
  private final TenantLookup tenants;
  private final UserRepository users;
  private final AccountRepository accounts;
  private final PasswordCredentialRepository credentials;
  private final PasswordPolicy policy;
  private final PasswordHasher hasher;
  private final LoginAttemptPolicy attempts;
  private final AuditRecorder audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  public AuthService(
      Authenticator authenticator,
      OpaqueTokenService tokens,
      InternalJwtService jwt,
      TenantLookup tenants,
      UserRepository users,
      AccountRepository accounts,
      PasswordCredentialRepository credentials,
      PasswordPolicy policy,
      PasswordHasher hasher,
      LoginAttemptPolicy attempts,
      AuditRecorder audit,
      TransactionTemplate tx,
      Clock clock) {
    this.authenticator = authenticator;
    this.tokens = tokens;
    this.jwt = jwt;
    this.tenants = tenants;
    this.users = users;
    this.accounts = accounts;
    this.credentials = credentials;
    this.policy = policy;
    this.hasher = hasher;
    this.attempts = attempts;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  public LoginResult login(String tenantSlug, String username, String password) {
    AuthenticatedIdentity identity;
    try {
      identity = authenticator.authenticate(new LoginRequest(tenantSlug, username, password));
    } catch (DomainException e) {
      audit.failure(
          "anonymous",
          "login.failure",
          null,
          "tenant:" + safe(tenantSlug) + " username:" + safe(username));
      throw e;
    }
    IssuedToken issued =
        tokens.issue(identity.account().id(), identity.user().id(), identity.tenant().id());
    audit.success(
        identity.user().tenantUserId(),
        "login.success",
        identity.tenant().id(),
        "account:" + identity.account().username());
    return new LoginResult(
        issued.value(),
        issued.token().expiresAt(),
        identity.mustChangePassword(),
        identity.user().tenantUserId());
  }

  public void logout(String tokenValue) {
    Optional<OpaqueToken> revoked = tokens.revoke(tokenValue);
    revoked.ifPresent(
        token ->
            audit.success(
                users.findById(token.userId()).map(User::tenantUserId).orElse("anonymous"),
                "logout",
                token.tenantId(),
                "sid:" + token.sid()));
  }

  public LoginResult changePassword(String tokenValue, String currentPassword, String newPassword) {
    OpaqueToken token =
        tokens.authenticate(tokenValue).orElseThrow(() -> unauthorized("Invalid session id"));
    Account account =
        accounts.findById(token.accountId()).orElseThrow(() -> unauthorized("Invalid session id"));
    PasswordCredential credential =
        credentials
            .findByAccountId(account.id())
            .orElseThrow(() -> unauthorized("Invalid session id"));
    Instant now = clock.instant();

    if (attempts.isLocked(credential, now)
        || !hasher.matches(
            currentPassword == null ? "" : currentPassword, credential.passwordHash())) {
      if (!attempts.isLocked(credential, now)) {
        LoginAttemptPolicy.Failure failure = attempts.afterFailure(credential, now);
        credentials.markFailure(account.id(), failure.failedCount(), failure.lockedUntil());
      }
      throw new DomainException(
          ErrorCode.INVALID_CREDENTIALS, PasswordAuthenticator.INVALID_MESSAGE);
    }
    policy.validate(newPassword, account.username());

    String newHash = hasher.hash(newPassword);
    IssuedToken replacement =
        tx.execute(
            status -> {
              credentials.changePassword(account.id(), newHash, now);
              tokens.revoke(tokenValue);
              return tokens.issue(account.id(), token.userId(), token.tenantId());
            });
    String tenantUserId =
        users.findById(token.userId()).map(User::tenantUserId).orElse("anonymous");
    audit.success(
        tenantUserId, "password.change", token.tenantId(), "account:" + account.username());
    return new LoginResult(
        replacement.value(), replacement.token().expiresAt(), false, tenantUserId);
  }

  /**
   * Swaps a valid Opaque Token for a short-lived Internal JWT (not allowed before the first
   * password change).
   */
  public ExchangeResult exchange(String tokenValue) {
    OpaqueToken token =
        tokens.authenticate(tokenValue).orElseThrow(() -> unauthorized("Invalid session id"));
    if (mustChange(token)) {
      throw new DomainException(
          ErrorCode.PASSWORD_CHANGE_REQUIRED, "The initial password must be changed first");
    }
    User user =
        users.findById(token.userId()).orElseThrow(() -> unauthorized("Invalid session id"));
    IssuedJwt issued = jwt.issue(user.tenantUserId(), token.tenantId(), token.sid());
    audit.success(user.tenantUserId(), "token.exchange", token.tenantId(), "sid:" + token.sid());
    return new ExchangeResult(issued.jwt(), issued.expiresAt());
  }

  /**
   * Describes the caller; for Internal JWTs the Session is found through {@code sid} (display
   * only).
   */
  public Me me(AuthenticatedPrincipal principal) {
    OpaqueToken token =
        tokens.findBySid(principal.sid()).orElseThrow(() -> unauthorized("Unknown session"));
    User user = users.findById(token.userId()).orElseThrow(() -> unauthorized("Unknown session"));
    Account account =
        accounts.findById(token.accountId()).orElseThrow(() -> unauthorized("Unknown session"));
    Tenant tenant =
        tenants.findById(token.tenantId()).orElseThrow(() -> unauthorized("Unknown session"));
    return new Me(
        user.tenantUserId(),
        user.displayName(),
        account.username(),
        tenant.slug(),
        mustChange(token));
  }

  public boolean passwordChangeRequired(AuthenticatedPrincipal principal) {
    return tokens.findBySid(principal.sid()).map(this::mustChange).orElse(false);
  }

  private boolean mustChange(OpaqueToken token) {
    return credentials
        .findByAccountId(token.accountId())
        .map(PasswordCredential::mustChangePassword)
        .orElse(false);
  }

  private static String safe(String value) {
    if (value == null) {
      return "";
    }
    String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "");
    return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
  }

  private static DomainException unauthorized(String message) {
    return new DomainException(ErrorCode.UNAUTHORIZED, message);
  }
}
