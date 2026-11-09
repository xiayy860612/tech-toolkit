package tech.toolkit.tenantcore;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.audit.AuditRecorder;
import tech.toolkit.tenantcore.common.IdGenerator;
import tech.toolkit.tenantcore.tenant.SubscriptionRepository;
import tech.toolkit.tenantcore.tenant.SubscriptionService;
import tech.toolkit.tenantcore.tenant.TenantDetailService;
import tech.toolkit.tenantcore.tenant.TenantQueryService;
import tech.toolkit.tenantcore.tenant.TenantRepository;
import tech.toolkit.tenantcore.tenant.TenantService;
import tech.toolkit.tenantcore.uam.AccountRepository;
import tech.toolkit.tenantcore.uam.AccountService;
import tech.toolkit.tenantcore.uam.AuthService;
import tech.toolkit.tenantcore.uam.InternalJwtService;
import tech.toolkit.tenantcore.uam.JwksPublisher;
import tech.toolkit.tenantcore.uam.LoginAttemptPolicy;
import tech.toolkit.tenantcore.uam.LoginMethodRepository;
import tech.toolkit.tenantcore.uam.LoginMethodService;
import tech.toolkit.tenantcore.uam.OpaqueTokenRepository;
import tech.toolkit.tenantcore.uam.OpaqueTokenService;
import tech.toolkit.tenantcore.uam.PasswordAuthenticator;
import tech.toolkit.tenantcore.uam.PasswordCredentialRepository;
import tech.toolkit.tenantcore.uam.PasswordHasher;
import tech.toolkit.tenantcore.uam.PasswordPolicy;
import tech.toolkit.tenantcore.uam.PrincipalResolver;
import tech.toolkit.tenantcore.uam.SigningKeyProvider;
import tech.toolkit.tenantcore.uam.UamSettings;
import tech.toolkit.tenantcore.uam.UserQueryService;
import tech.toolkit.tenantcore.uam.UserRepository;
import tech.toolkit.tenantcore.uam.UserService;

/**
 * Wires the tenant, uam and audit packages together without a DI container, so the API (which
 * exposes these as beans) and the Bootstrap CLI share exactly one set of business rules.
 */
public class CoreComponents {

  private final AuditRecorder audit;
  private final TenantService tenants;
  private final TenantQueryService tenantQueries;
  private final SubscriptionService subscriptions;
  private final TenantDetailService tenantDetails;
  private final LoginMethodService loginMethods;
  private final UserService users;
  private final AccountService accounts;
  private final UserQueryService userQueries;
  private final OpaqueTokenService opaqueTokens;
  private final InternalJwtService jwt;
  private final JwksPublisher jwks;
  private final AuthService auth;
  private final PrincipalResolver principalResolver;

  /** For callers that never sign tokens (the Bootstrap CLI): UAM defaults with an ephemeral key. */
  public CoreComponents(DataSource dataSource, Clock clock) {
    this(dataSource, clock, UamSettings.ephemeral());
  }

  public CoreComponents(DataSource dataSource, Clock clock, UamSettings settings) {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    IdGenerator ids = new IdGenerator();
    PasswordPolicy passwordPolicy = new PasswordPolicy();
    PasswordHasher hasher = new PasswordHasher();
    LoginAttemptPolicy attempts = LoginAttemptPolicy.defaults();
    this.audit = new AuditRecorder(jdbc, clock);

    TenantRepository tenantRepo = new TenantRepository(jdbc);
    SubscriptionRepository subscriptionRepo = new SubscriptionRepository(jdbc);
    LoginMethodRepository loginMethodRepo = new LoginMethodRepository(jdbc);
    UserRepository userRepo = new UserRepository(jdbc);
    AccountRepository accountRepo = new AccountRepository(jdbc);
    PasswordCredentialRepository credentialRepo = new PasswordCredentialRepository(jdbc);

    this.tenants = new TenantService(tenantRepo, subscriptionRepo, audit, clock);
    this.tenantQueries = new TenantQueryService(tenantRepo, subscriptionRepo);
    this.subscriptions = new SubscriptionService(tenantRepo, subscriptionRepo, audit, clock);
    this.loginMethods = new LoginMethodService(tenants, loginMethodRepo, audit, clock);
    this.users = new UserService(tenants, userRepo, ids, audit, clock);
    this.userQueries = new UserQueryService(userRepo, accountRepo, loginMethodRepo, tenantQueries);
    this.tenantDetails = new TenantDetailService(tenantRepo, subscriptionRepo, userQueries);
    this.accounts =
        new AccountService(
            tenants,
            loginMethodRepo,
            userRepo,
            accountRepo,
            credentialRepo,
            passwordPolicy,
            hasher,
            audit,
            tx,
            clock);

    SigningKeyProvider signingKeys = new SigningKeyProvider(settings);
    this.opaqueTokens = new OpaqueTokenService(new OpaqueTokenRepository(jdbc), ids, clock);
    this.jwt = new InternalJwtService(signingKeys, settings, clock);
    this.jwks = new JwksPublisher(signingKeys);
    PasswordAuthenticator authenticator =
        new PasswordAuthenticator(
            tenants,
            loginMethodRepo,
            userRepo,
            accountRepo,
            credentialRepo,
            hasher,
            attempts,
            clock);
    this.auth =
        new AuthService(
            authenticator,
            opaqueTokens,
            jwt,
            tenants,
            userRepo,
            accountRepo,
            credentialRepo,
            passwordPolicy,
            hasher,
            attempts,
            audit,
            tx,
            clock);
    this.principalResolver = new PrincipalResolver(opaqueTokens, jwt, userRepo);
  }

  public AuditRecorder audit() {
    return audit;
  }

  public TenantService tenants() {
    return tenants;
  }

  public TenantQueryService tenantQueries() {
    return tenantQueries;
  }

  public SubscriptionService subscriptions() {
    return subscriptions;
  }

  public TenantDetailService tenantDetails() {
    return tenantDetails;
  }

  public LoginMethodService loginMethods() {
    return loginMethods;
  }

  public UserService users() {
    return users;
  }

  public AccountService accounts() {
    return accounts;
  }

  public UserQueryService userQueries() {
    return userQueries;
  }

  public OpaqueTokenService opaqueTokens() {
    return opaqueTokens;
  }

  public InternalJwtService jwt() {
    return jwt;
  }

  public JwksPublisher jwks() {
    return jwks;
  }

  public AuthService auth() {
    return auth;
  }

  public PrincipalResolver principalResolver() {
    return principalResolver;
  }
}
