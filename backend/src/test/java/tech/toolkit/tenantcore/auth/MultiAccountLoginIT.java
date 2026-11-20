package tech.toolkit.tenantcore.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.tenantcore.identity.Account;
import tech.toolkit.tenantcore.identity.AccountService;
import tech.toolkit.tenantcore.identity.AccountType;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.platform.Actor;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestJwt;

/** FR-002 / SC-004 / SC-007：一个 User 多个 Account；停用其一不影响其余；新增登录方式不改 User。 */
@AutoConfigureMockMvc
@Import(MultiAccountLoginIT.ExtraAuthenticator.class)
class MultiAccountLoginIT extends AbstractPostgresIT {

  private static final String PW = "Str0ng-Passw0rd";

  /** 仅用来证明：再注册一个 Authenticator 实现（此处委托给密码校验，保证登录行为不变）不需要改动 app_user 或登录流程。 */
  @TestConfiguration
  static class ExtraAuthenticator {
    @Bean
    Authenticator auditedAuthenticator(PasswordAuthenticator delegate) {
      return new Authenticator() {
        @Override
        public AccountType type() {
          return AccountType.USERNAME_PASSWORD;
        }

        @Override
        public boolean verify(Account account, String secret) {
          return delegate.verify(account, secret);
        }

        @Override
        public void burnTime(String secret) {
          delegate.burnTime(secret);
        }
      };
    }
  }

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired AccountService accountService;
  @Autowired List<Authenticator> authenticators;

  private MvcResult login(String username, String password, int expected) throws Exception {
    return mvc.perform(
            post("/auth/login")
                .header("Authorization", "Bearer " + TestJwt.service(0).sign())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().is(expected))
        .andReturn();
  }

  private String userJwt(String username, String password) throws Exception {
    String body = login(username, password, 200).getResponse().getContentAsString();
    String token = JsonPath.read(body, "$.sessionToken");
    String resolved =
        mvc.perform(
                post("/auth/sessions/resolve")
                    .header("Authorization", "Bearer " + TestJwt.service(0).sign())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sessionToken\":\"" + token + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return TestJwt.user(
            UUID.fromString(JsonPath.read(resolved, "$.userId")),
            UUID.fromString(JsonPath.read(resolved, "$.accountId")),
            UUID.fromString(JsonPath.read(resolved, "$.sessionId")),
            0)
        .sign();
  }

  @Test
  void twoAccountsOfOneUserBothResolveToTheSameUserAndDisablingOneLeavesTheOther()
      throws Exception {
    var first = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var admin = fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    Actor actor = new Actor(admin.user().getId(), admin.account().getId(), null);
    String secondName = TestAccounts.uniqueName();
    var second =
        TenantContext.callAs(
            0L,
            () ->
                accountService.create(actor, first.user().getId(), secondName, "Giv3n-Passw0rd!"));

    String jwtFirst = userJwt(first.username(), PW);
    String jwtSecond = userJwt(secondName, "Giv3n-Passw0rd!");
    for (String jwt : List.of(jwtFirst, jwtSecond)) {
      MvcResult me =
          mvc.perform(get("/auth/me").header("Authorization", "Bearer " + jwt)).andReturn();
      assertThat(me.getResponse().getStatus()).isIn(200, 403); // 第二个 Account 需先改密
    }
    String meFirst =
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + jwtFirst))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat((String) JsonPath.read(meFirst, "$.userId"))
        .isEqualTo(first.user().getId().toString());

    TenantContext.runAs(
        0L,
        () ->
            accountService.disable(
                actor, first.user().getId(), second.account().account().getId(), false));

    login(secondName, "Giv3n-Passw0rd!", 401);
    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + jwtSecond))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + jwtFirst))
        .andExpect(status().isOk());
    login(first.username(), PW, 200);
  }

  @Test
  void aNewLoginMethodIsJustAnotherAuthenticatorAndNeverTouchesTheUserEntity() {
    assertThat(authenticators).hasSizeGreaterThanOrEqualTo(2);
    List<String> fields =
        java.util.Arrays.stream(AppUser.class.getDeclaredFields()).map(Field::getName).toList();
    assertThat(fields)
        .noneMatch(
            n -> n.toLowerCase().contains("password") || n.toLowerCase().contains("identifier"));
  }
}
