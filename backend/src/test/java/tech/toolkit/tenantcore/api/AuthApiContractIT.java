package tech.toolkit.tenantcore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tech.toolkit.tenantcore.support.OpenApiContract.valid;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.identity.AccountRepository;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.identity.Status;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.MutableClock;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestJwt;

@AutoConfigureMockMvc
class AuthApiContractIT extends AbstractPostgresIT {

  private static final String PW = "Str0ng-Passw0rd";

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired AccountRepository accounts;
  @Autowired AppUserRepository users;
  @Autowired TransactionTemplate tx;
  @Autowired MutableClock clock;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

  @BeforeEach
  void resetClock() {
    clock.reset();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private MockHttpServletRequestBuilder service(MockHttpServletRequestBuilder b) {
    return b.header("Authorization", bearer(TestJwt.service(0L).sign()))
        .contentType(MediaType.APPLICATION_JSON);
  }

  private MvcResult login(String username, String password, int expectedStatus) throws Exception {
    return mvc.perform(
            service(post("/auth/login"))
                .content(
                    "{\"username\":\""
                        + username
                        + "\",\"password\":\""
                        + password
                        + "\",\"userAgent\":\"UA-test\"}"))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }

  /** 登录并解析出会话，返回可用于调用用户端点的 JWT。 */
  private record Login(String token, String userJwt, String sessionId, String accountId) {}

  private Login loginAndResolve(String username, String password) throws Exception {
    String body = login(username, password, 200).getResponse().getContentAsString();
    String token = JsonPath.read(body, "$.sessionToken");
    MvcResult resolved =
        mvc.perform(
                service(post("/auth/sessions/resolve"))
                    .content("{\"sessionToken\":\"" + token + "\"}"))
            .andExpect(status().isOk())
            .andExpect(valid())
            .andReturn();
    String r = resolved.getResponse().getContentAsString();
    String sid = JsonPath.read(r, "$.sessionId");
    String account = JsonPath.read(r, "$.accountId");
    String user = JsonPath.read(r, "$.userId");
    String jwt =
        TestJwt.user(UUID.fromString(user), UUID.fromString(account), UUID.fromString(sid), 0L)
            .sign();
    return new Login(token, jwt, sid, account);
  }

  private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder b, String jwt) {
    return b.header("Authorization", bearer(jwt)).contentType(MediaType.APPLICATION_JSON);
  }

  @Test
  void loginReturnsAOneTimeTokenMatchingTheContractAndStoresOnlyItsHash() throws Exception {
    var acc = fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, true);
    MvcResult result =
        mvc.perform(
                service(post("/auth/login"))
                    .content(
                        "{\"username\":\""
                            + acc.username()
                            + "\",\"password\":\""
                            + PW
                            + "\",\"userAgent\":\"UA-test\"}"))
            .andExpect(status().isOk())
            .andExpect(valid())
            .andExpect(jsonPath("$.me.role").value("TENANT_ADMIN"))
            .andExpect(jsonPath("$.me.tenantId").value(0))
            .andExpect(jsonPath("$.me.mustChangePassword").value(true))
            .andExpect(jsonPath("$.sessionToken").isNotEmpty())
            .andExpect(jsonPath("$.expiresAt").isNotEmpty())
            .andReturn();
    String token = JsonPath.read(result.getResponse().getContentAsString(), "$.sessionToken");

    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    assertThat(
            owner.queryForObject(
                "select count(*) from session where token_hash = ?", Integer.class, hash))
        .isEqualTo(1);
    assertThat(
            owner.queryForObject(
                "select count(*) from session where user_agent = 'UA-test' and device = '未知设备'",
                Integer.class))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void everyKindOfLoginFailureReturnsTheSameBody() throws Exception {
    var wrong = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var accountOff = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var userOff = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var locked = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s -> {
                  accounts
                      .findById(accountOff.account().getId())
                      .orElseThrow()
                      .setStatus(Status.DISABLED);
                  users.findById(userOff.user().getId()).orElseThrow().setStatus(Status.DISABLED);
                  accounts
                      .findById(locked.account().getId())
                      .orElseThrow()
                      .setLockedUntil(clock.instant().plusSeconds(600));
                }));

    String unknown =
        login("ghost-" + TestAccounts.uniqueName(), PW, 401).getResponse().getContentAsString();
    for (String[] c :
        new String[][] {
          {wrong.username(), "wrong-password"},
          {accountOff.username(), PW},
          {userOff.username(), PW},
          {locked.username(), PW}
        }) {
      MvcResult r = login(c[0], c[1], 401);
      assertThat(r.getResponse().getContentAsString()).isEqualTo(unknown);
      mvc.perform(
              service(post("/auth/login"))
                  .content("{\"username\":\"" + c[0] + "\",\"password\":\"" + c[1] + "\"}"))
          .andExpect(valid())
          .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
  }

  @Test
  void resolveAcceptsValidTokensAndRejectsUnknownOrRevokedOnes() throws Exception {
    var acc = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    Login l = loginAndResolve(acc.username(), PW);

    mvc.perform(
            service(post("/auth/sessions/resolve"))
                .content("{\"sessionToken\":\"" + "x".repeat(43) + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(valid());

    mvc.perform(asUser(post("/auth/logout"), l.userJwt()))
        .andExpect(status().isNoContent())
        .andExpect(valid());
    mvc.perform(
            service(post("/auth/sessions/resolve"))
                .content("{\"sessionToken\":\"" + l.token() + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void meReturnsTheCurrentUserWithTheRoleFromTheDatabase() throws Exception {
    var acc = fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    Login l = loginAndResolve(acc.username(), PW);
    mvc.perform(asUser(get("/auth/me"), l.userJwt()))
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.role").value("TENANT_ADMIN"))
        .andExpect(jsonPath("$.mustChangePassword").value(false))
        .andExpect(jsonPath("$.displayName").value("Test " + acc.username()));
  }

  @Test
  void logoutRevokesTheCurrentSessionAndAudits() throws Exception {
    var acc = fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    Login l = loginAndResolve(acc.username(), PW);
    mvc.perform(asUser(post("/auth/logout"), l.userJwt())).andExpect(status().isNoContent());
    mvc.perform(asUser(get("/auth/me"), l.userJwt())).andExpect(status().isUnauthorized());
    assertThat(
            owner.queryForObject(
                "select count(*) from audit_log where action='LOGOUT' and actor_account_id=?",
                Integer.class,
                UUID.fromString(l.accountId())))
        .isEqualTo(1);
  }

  @Test
  void changingThePasswordKeepsTheCurrentSessionRevokesOthersAndClearsTheForcedChange()
      throws Exception {
    var acc = fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, true);
    Login current = loginAndResolve(acc.username(), PW);
    Login other = loginAndResolve(acc.username(), PW);

    String newPw = "N3w-Passw0rd-Longer";
    mvc.perform(
            asUser(post("/auth/change-password"), current.userJwt())
                .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + newPw + "\"}"))
        .andExpect(status().isNoContent())
        .andExpect(valid());

    mvc.perform(asUser(get("/auth/me"), current.userJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mustChangePassword").value(false));
    mvc.perform(asUser(get("/auth/me"), other.userJwt())).andExpect(status().isUnauthorized());
    login(acc.username(), PW, 401);
    login(acc.username(), newPw, 200);
  }

  @Test
  void changePasswordRejectsAWrongCurrentPasswordAndWeakNewPasswords() throws Exception {
    var acc = fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    Login l = loginAndResolve(acc.username(), PW);

    mvc.perform(
            asUser(post("/auth/change-password"), l.userJwt())
                .content("{\"currentPassword\":\"nope\",\"newPassword\":\"N3w-Passw0rd-Longer\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));

    mvc.perform(
            asUser(post("/auth/change-password"), l.userJwt())
                .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"short\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("newPassword"));

    mvc.perform(
            asUser(post("/auth/change-password"), l.userJwt())
                .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + PW + "\"}"))
        .andExpect(status().isBadRequest()); // 不得与当前密码相同
  }
}
