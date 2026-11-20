package tech.toolkit.tenantcore.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.auth.RevokeReason;
import tech.toolkit.tenantcore.auth.Session;
import tech.toolkit.tenantcore.auth.SessionService;
import tech.toolkit.tenantcore.identity.Account;
import tech.toolkit.tenantcore.identity.AccountRepository;
import tech.toolkit.tenantcore.identity.AccountType;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.PasswordCredential;
import tech.toolkit.tenantcore.identity.PasswordCredentialRepository;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.identity.Status;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.MutableClock;
import tech.toolkit.tenantcore.support.TestJwt;

@AutoConfigureMockMvc
@ActiveProfiles("stub")
class JwtAuthenticationFilterIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserRepository users;
  @Autowired AccountRepository accounts;
  @Autowired PasswordCredentialRepository credentials;
  @Autowired SessionService sessions;
  @Autowired MutableClock clock;
  @Autowired TransactionTemplate tx;

  record Fixture(UUID userId, UUID accountId, Session session) {}

  @BeforeEach
  void resetClock() {
    clock.reset();
  }

  private Fixture fixture(Role role, boolean mustChange) {
    return TenantContext.callAs(
        0L,
        () -> {
          AppUser user = users.save(new AppUser("tester", null, null, role));
          Account account =
              accounts.save(
                  new Account(
                      user.getId(),
                      AccountType.USERNAME_PASSWORD,
                      "u" + UUID.randomUUID().toString().substring(0, 8)));
          credentials.save(new PasswordCredential(account.getId(), "{noop}x", mustChange));
          try {
            Session s =
                sessions
                    .create(
                        user.getId(), account.getId(), InetAddress.getByName("203.0.113.1"), "UA")
                    .session();
            return new Fixture(user.getId(), account.getId(), s);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private TestJwt jwtOf(Fixture f) {
    return TestJwt.user(f.userId(), f.accountId(), f.session().getId(), 0L);
  }

  private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder b, String token) {
    return b.header("Authorization", "Bearer " + token);
  }

  @Test
  void tenantContextComesOnlyFromTheTokenAndIgnoresRequestSuppliedTenants() throws Exception {
    Fixture f = fixture(Role.TENANT_ADMIN, false);
    mvc.perform(
            withToken(get("/_test/tenant"), jwtOf(f).sign())
                .header("X-Tenant-Id", "99")
                .header("X-Tenant", "99")
                .param("tenantId", "99"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant").value(0));
  }

  @Test
  void missingInvalidOrExpiredTokensAreUnauthorized() throws Exception {
    Fixture f = fixture(Role.TENANT_ADMIN, false);
    mvc.perform(get("/_test/admin"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(withToken(get("/_test/admin"), "garbage")).andExpect(status().isUnauthorized());
    mvc.perform(
            withToken(
                get("/_test/admin"), jwtOf(f).expiresAt(Instant.now().minusSeconds(300)).sign()))
        .andExpect(status().isUnauthorized());
    mvc.perform(withToken(get("/_test/admin"), jwtOf(f).unsigned()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void disabledUserOrAccountIsUnauthorized() throws Exception {
    Fixture a = fixture(Role.TENANT_ADMIN, false);
    mvc.perform(withToken(get("/_test/admin"), jwtOf(a).sign())).andExpect(status().isOk());
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s -> users.findById(a.userId()).orElseThrow().setStatus(Status.DISABLED)));
    mvc.perform(withToken(get("/_test/admin"), jwtOf(a).sign()))
        .andExpect(status().isUnauthorized());

    Fixture b = fixture(Role.TENANT_ADMIN, false);
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s -> accounts.findById(b.accountId()).orElseThrow().setStatus(Status.DISABLED)));
    mvc.perform(withToken(get("/_test/admin"), jwtOf(b).sign()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void revokedExpiredOrMismatchedSessionsAreUnauthorized() throws Exception {
    Fixture revoked = fixture(Role.TENANT_ADMIN, false);
    TenantContext.runAs(
        0L, () -> sessions.revoke(revoked.session().getId(), RevokeReason.ADMIN_REVOKED));
    mvc.perform(withToken(get("/_test/admin"), jwtOf(revoked).sign()))
        .andExpect(status().isUnauthorized());

    Fixture mismatched = fixture(Role.TENANT_ADMIN, false);
    Fixture other = fixture(Role.TENANT_ADMIN, false);
    // sid 属于另一个用户的会话
    String forged =
        TestJwt.user(mismatched.userId(), mismatched.accountId(), other.session().getId(), 0L)
            .sign();
    mvc.perform(withToken(get("/_test/admin"), forged)).andExpect(status().isUnauthorized());

    Fixture unknownSession = fixture(Role.TENANT_ADMIN, false);
    String nonexistent =
        TestJwt.user(unknownSession.userId(), unknownSession.accountId(), UUID.randomUUID(), 0L)
            .sign();
    mvc.perform(withToken(get("/_test/admin"), nonexistent)).andExpect(status().isUnauthorized());
  }

  @Test
  void validRequestsSlideTheIdleTimeoutAndIdleSessionsExpire() throws Exception {
    Fixture f = fixture(Role.TENANT_ADMIN, false);
    clock.advance(Duration.ofMinutes(29));
    mvc.perform(withToken(get("/_test/admin"), jwtOf(f).at(clock.instant()).sign()))
        .andExpect(status().isOk());
    clock.advance(Duration.ofMinutes(29));
    mvc.perform(withToken(get("/_test/admin"), jwtOf(f).at(clock.instant()).sign()))
        .andExpect(status().isOk());
    clock.advance(Duration.ofMinutes(31));
    mvc.perform(withToken(get("/_test/admin"), jwtOf(f).at(clock.instant()).sign()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void serviceTokensMayOnlyCallLoginAndSessionResolution() throws Exception {
    String service = TestJwt.service(0L).sign();
    mvc.perform(withToken(post("/auth/login"), service)).andExpect(status().isOk());
    mvc.perform(withToken(post("/auth/sessions/resolve"), service)).andExpect(status().isOk());
    mvc.perform(withToken(get("/_test/admin"), service)).andExpect(status().isUnauthorized());
    mvc.perform(withToken(get("/auth/me"), service)).andExpect(status().isUnauthorized());
  }

  @Test
  void userTokensCannotCallServiceOnlyEndpoints() throws Exception {
    Fixture f = fixture(Role.TENANT_ADMIN, false);
    mvc.perform(withToken(post("/auth/login"), jwtOf(f).sign()))
        .andExpect(status().isUnauthorized());
    mvc.perform(withToken(post("/auth/sessions/resolve"), jwtOf(f).sign()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void memberIsForbiddenFromAdminEndpointsButCanUseAuthEndpoints() throws Exception {
    Fixture f = fixture(Role.MEMBER, false);
    mvc.perform(withToken(get("/_test/admin"), jwtOf(f).sign()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(withToken(get("/auth/me"), jwtOf(f).sign())).andExpect(status().isOk());
  }

  @Test
  void roleIsReadFromTheDatabaseSoDemotionTakesEffectOnTheNextRequest() throws Exception {
    Fixture f = fixture(Role.TENANT_ADMIN, false);
    String token = jwtOf(f).sign();
    mvc.perform(withToken(get("/_test/admin"), token)).andExpect(status().isOk());
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s -> users.findById(f.userId()).orElseThrow().setRole(Role.MEMBER)));
    mvc.perform(withToken(get("/_test/admin"), token)).andExpect(status().isForbidden());
  }

  @Test
  void mustChangePasswordRestrictsAccessToPasswordEndpoints() throws Exception {
    Fixture f = fixture(Role.TENANT_ADMIN, true);
    String token = jwtOf(f).sign();
    mvc.perform(withToken(get("/_test/admin"), token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    mvc.perform(withToken(get("/auth/me"), token)).andExpect(status().isOk());
    mvc.perform(withToken(post("/auth/change-password"), token)).andExpect(status().isOk());
    mvc.perform(withToken(post("/auth/logout"), token)).andExpect(status().isOk());
  }
}
