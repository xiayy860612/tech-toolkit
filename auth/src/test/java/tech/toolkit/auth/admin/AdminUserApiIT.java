package tech.toolkit.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.AdminApiTestBase;
import tools.jackson.databind.JsonNode;

/** FR-019, FR-019a: the administrator's user API, protected by the {@code auth.admin} scope. */
class AdminUserApiIT extends AdminApiTestBase {

  private MvcResult createUser(String username, String password, boolean admin) throws Exception {
    String body =
        "{\"username\":\"%s\",\"initialPassword\":\"%s\",\"admin\":%s}"
            .formatted(username, password, admin);
    return call(post("/admin/v1/users").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  // ---- who may call it

  @Test
  void withoutATokenTheApiAnswers401() throws Exception {
    assertThat(mvc.perform(get("/admin/v1/users")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
  }

  @Test
  void aTokenWithoutTheAdminScopeAnswers403() throws Exception {
    String ordinary = obtainTokens().get("access_token").asString();

    MvcResult result = mvc.perform(withToken(get("/admin/v1/users"), ordinary)).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(403);
  }

  @Test
  void aNormalUserAskingForTheAdminScopeDoesNotGetIt() throws Exception {
    String token = tokenOf(USERNAME, PASSWORD, "openid auth.admin");

    String scope = SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("scope");
    MvcResult result = mvc.perform(withToken(get("/admin/v1/users"), token)).andReturn();

    assertThat(scope).doesNotContain("auth.admin");
    assertThat(result.getResponse().getStatus()).isEqualTo(403);
  }

  @Test
  void anAdministratorWhoIsNoLongerOneIsRefusedAtOnceWithTheSameToken() throws Exception {
    assertThat(call(get("/admin/v1/users")).getResponse().getStatus()).isEqualTo(200);

    ownerJdbc().update("update auth_user set is_admin = false where username = ?", ADMIN);

    assertThat(call(get("/admin/v1/users")).getResponse().getStatus()).isEqualTo(403);
  }

  @Test
  void aDisabledAdministratorIsRefusedAtOnce() throws Exception {
    ownerJdbc().update("update auth_user set status = 'DISABLED' where username = ?", ADMIN);

    assertThat(call(get("/admin/v1/users")).getResponse().getStatus()).isEqualTo(403);
  }

  // ---- creating and reading

  @Test
  void createsAUserWhoMustChangeThePassword() throws Exception {
    MvcResult result = createUser("Bob.Smith", "Initial-Passw0rd-1", false);

    assertThat(result.getResponse().getStatus()).isEqualTo(201);
    JsonNode user = json(result);
    assertThat(user.get("username").asString()).isEqualTo("bob.smith");
    assertThat(user.get("status").asString()).isEqualTo("ENABLED");
    assertThat(user.get("admin").asBoolean()).isFalse();
    assertThat(user.get("mustChangePassword").asBoolean()).isTrue();
    assertThat(user.has("passwordHash")).isFalse();
    var audit =
        ownerJdbc()
            .queryForMap(
                "select actor, subject_user_id from auth_audit_event where type = 'USER_CREATED' and actor <> 'bootstrap'");
    assertThat(audit.get("actor")).isEqualTo(String.valueOf(idOf(ADMIN)));
    assertThat(audit.get("subject_user_id")).isEqualTo(idOf("bob.smith"));
  }

  @Test
  void aDuplicateUsernameAnswers409WhateverTheCase() throws Exception {
    createUser("bob.smith", "Initial-Passw0rd-1", false);

    MvcResult result = createUser("BOB.SMITH", "Initial-Passw0rd-2", false);

    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
  }

  @Test
  void aPasswordThatBreaksThePolicyAnswers400AndCreatesNothing() throws Exception {
    MvcResult result = createUser("carol", "short", false);

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(json(result).get("detail").asString()).contains("至少 12 位");
    assertThat(
            ownerJdbc()
                .queryForObject(
                    "select count(*) from auth_user where username = 'carol'", Integer.class))
        .isZero();
  }

  @Test
  void anInvalidUsernameAnswers400() throws Exception {
    assertThat(createUser("ab", "Initial-Passw0rd-1", false).getResponse().getStatus())
        .isEqualTo(400);
    assertThat(createUser("has space", "Initial-Passw0rd-1", false).getResponse().getStatus())
        .isEqualTo(400);
  }

  @Test
  void listsFiltersAndPagesUsers() throws Exception {
    createUser("dave.one", "Initial-Passw0rd-1", false);
    createUser("dave.two", "Initial-Passw0rd-1", false);
    ownerJdbc().update("update auth_user set status = 'DISABLED' where username = 'dave.two'");

    JsonNode byPrefix = json(call(get("/admin/v1/users").param("username", "dave")));
    JsonNode disabled = json(call(get("/admin/v1/users").param("status", "DISABLED")));
    JsonNode firstPage = json(call(get("/admin/v1/users").param("size", "2").param("page", "0")));

    assertThat(byPrefix.get("total").asLong()).isEqualTo(2);
    assertThat(byPrefix.get("items").size()).isEqualTo(2);
    assertThat(disabled.get("items").get(0).get("username").asString()).isEqualTo("dave.two");
    assertThat(firstPage.get("items").size()).isEqualTo(2);
    assertThat(firstPage.get("total").asLong()).isGreaterThanOrEqualTo(4);
  }

  @Test
  void readsOneUserAndAnswers404ForAnUnknownOne() throws Exception {
    assertThat(json(call(get("/admin/v1/users/" + idOf(USERNAME)))).get("username").asString())
        .isEqualTo(USERNAME);
    assertThat(call(get("/admin/v1/users/999999")).getResponse().getStatus()).isEqualTo(404);
  }

  // ---- changing a user

  @Test
  void disablingEndsTheUsersLoginsAndTokensAndEnablingRestoresTheLogin() throws Exception {
    JsonNode tokens = obtainTokens();

    MvcResult disabled = call(post("/admin/v1/users/" + idOf(USERNAME) + "/disable"));

    assertThat(disabled.getResponse().getStatus()).isEqualTo(204);
    assertThat(signIn(new Browser(), USERNAME, PASSWORD).getResponse().getRedirectedUrl())
        .isEqualTo("/login?error");
    assertThat(
            refresh(CLIENT1, SECRET1, tokens.get("refresh_token").asString())
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            ownerJdbc()
                .queryForObject(
                    "select count(*) from oauth2_authorization where principal_name = ?",
                    Integer.class,
                    String.valueOf(idOf(USERNAME))))
        .isZero();
    assertThat(
            ownerJdbc()
                .queryForObject(
                    "select count(*) from spring_session where principal_name = ?",
                    Integer.class,
                    String.valueOf(idOf(USERNAME))))
        .isZero();

    MvcResult enabled = call(post("/admin/v1/users/" + idOf(USERNAME) + "/enable"));

    assertThat(enabled.getResponse().getStatus()).isEqualTo(204);
    assertThat(signIn(new Browser(), USERNAME, PASSWORD).getResponse().getRedirectedUrl())
        .doesNotContain("error");
    assertThat(
            ownerJdbc()
                .queryForObject(
                    "select count(*) from auth_audit_event where type = 'USER_UPDATED'",
                    Integer.class))
        .isEqualTo(2);
  }

  @Test
  void unlockingLetsALockedUserInAgain() throws Exception {
    for (int i = 0; i < 5; i++) {
      signIn(new Browser(), USERNAME, "this-is-not-the-password");
    }
    assertThat(signIn(new Browser(), USERNAME, PASSWORD).getResponse().getRedirectedUrl())
        .isEqualTo("/login?error");

    MvcResult unlocked = call(post("/admin/v1/users/" + idOf(USERNAME) + "/unlock"));

    assertThat(unlocked.getResponse().getStatus()).isEqualTo(204);
    assertThat(signIn(new Browser(), USERNAME, PASSWORD).getResponse().getRedirectedUrl())
        .doesNotContain("error");
  }

  @Test
  void aPasswordResetMakesTheUserChangeItAtTheNextLogin() throws Exception {
    obtainTokens();

    MvcResult reset =
        call(
            post("/admin/v1/users/" + idOf(USERNAME) + "/password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"temporaryPassword\":\"Temporary-Passw0rd-1\"}"));

    assertThat(reset.getResponse().getStatus()).isEqualTo(204);
    assertThat(signIn(new Browser(), USERNAME, PASSWORD).getResponse().getRedirectedUrl())
        .isEqualTo("/login?error");
    assertThat(
            signIn(new Browser(), USERNAME, "Temporary-Passw0rd-1")
                .getResponse()
                .getRedirectedUrl())
        .endsWith("/account/password");
    assertThat(
            ownerJdbc()
                .queryForObject(
                    "select count(*) from oauth2_authorization where principal_name = ?",
                    Integer.class,
                    String.valueOf(idOf(USERNAME))))
        .isZero();
  }

  @Test
  void aWeakTemporaryPasswordIsRefusedAndChangesNothing() throws Exception {
    MvcResult reset =
        call(
            post("/admin/v1/users/" + idOf(USERNAME) + "/password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"temporaryPassword\":\"short\"}"));

    assertThat(reset.getResponse().getStatus()).isEqualTo(400);
    assertThat(signIn(new Browser(), USERNAME, PASSWORD).getResponse().getRedirectedUrl())
        .doesNotContain("error");
  }

  @Test
  void forcingASignOutEndsTheLoginsButNotTheAccount() throws Exception {
    Browser browser = new Browser();
    obtainCode(browser, CLIENT1, REDIRECT1, challengeOf(newVerifier()));

    MvcResult result = call(delete("/admin/v1/users/" + idOf(USERNAME) + "/sessions"));

    assertThat(result.getResponse().getStatus()).isEqualTo(204);
    String again = authorizeUri(CLIENT1, REDIRECT1, "s", challengeOf(newVerifier()));
    assertThat(authorize(browser, again).getResponse().getRedirectedUrl()).endsWith("/login");
    assertThat(signIn(new Browser(), USERNAME, PASSWORD).getResponse().getRedirectedUrl())
        .doesNotContain("error");
  }

  @Test
  void anUnknownUserAnswers404ForEveryAction() throws Exception {
    for (String action : List.of("disable", "enable", "unlock")) {
      assertThat(call(post("/admin/v1/users/999999/" + action)).getResponse().getStatus())
          .isEqualTo(404);
    }
    assertThat(call(delete("/admin/v1/users/999999/sessions")).getResponse().getStatus())
        .isEqualTo(404);
  }
}
