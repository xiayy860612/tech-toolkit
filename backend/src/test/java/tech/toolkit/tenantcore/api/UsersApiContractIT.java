package tech.toolkit.tenantcore.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tech.toolkit.tenantcore.support.OpenApiContract.valid;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestSessions;

@AutoConfigureMockMvc
class UsersApiContractIT extends AbstractPostgresIT {

  private static final String PW = "Str0ng-Passw0rd";

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;

  private TestSessions.Open admin;

  @BeforeEach
  void adminSession() {
    admin =
        sessions.open(
            0, fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false));
  }

  private ResultActions call(MockHttpServletRequestBuilder b, TestSessions.Open who, String body)
      throws Exception {
    b.header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON);
    if (body != null) {
      b.content(body);
    }
    return mvc.perform(b);
  }

  private static String newUserBody(String username, String extra) {
    return "{\"displayName\":\"Zed Zhang\",\"role\":\"MEMBER\",\"username\":\""
        + username
        + "\""
        + extra
        + "}";
  }

  private String createUser(String username) throws Exception {
    String json =
        call(post("/users"), admin, newUserBody(username, ""))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return String.valueOf(((Number) JsonPath.read(json, "$.user.id")).longValue());
  }

  @Test
  void createReturnsTheGeneratedPasswordOnlyOnce() throws Exception {
    String name = TestAccounts.uniqueName();
    String json =
        call(post("/users"), admin, newUserBody(name, ""))
            .andExpect(status().isCreated())
            .andExpect(valid())
            .andExpect(jsonPath("$.user.username").value(name))
            .andExpect(jsonPath("$.user.mustChangePassword").value(true))
            .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = String.valueOf(((Number) JsonPath.read(json, "$.user.id")).longValue());
    String generated = JsonPath.read(json, "$.temporaryPassword");

    call(get("/users/" + id), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(generated))));
  }

  @Test
  void aSuppliedPasswordIsNotEchoed() throws Exception {
    call(
            post("/users"),
            admin,
            newUserBody(TestAccounts.uniqueName(), ",\"temporaryPassword\":\"Giv3n-Passw0rd!\""))
        .andExpect(status().isCreated())
        .andExpect(valid())
        .andExpect(jsonPath("$.temporaryPassword").doesNotExist());
  }

  @Test
  void invalidInputAndConflictsUseProblemJson() throws Exception {
    String name = TestAccounts.uniqueName();
    createUser(name);
    call(post("/users"), admin, newUserBody(name.toUpperCase(), ""))
        .andExpect(status().isConflict())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    call(post("/users"), admin, newUserBody("a", ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    call(
            post("/users"),
            admin,
            "{\"displayName\":\"\",\"role\":\"MEMBER\",\"username\":\"validname\"}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void listSupportsKeywordStatusAndPaging() throws Exception {
    String name = "kw" + TestAccounts.uniqueName();
    createUser(name);
    call(get("/users").param("keyword", name), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].username").value(name))
        .andExpect(jsonPath("$.size").value(20));
    call(get("/users").param("status", "DISABLED").param("keyword", name), admin, null)
        .andExpect(jsonPath("$.total").value(0));
    call(get("/users").param("size", "1").param("page", "0"), admin, null)
        .andExpect(valid())
        .andExpect(jsonPath("$.items.length()").value(1));
    for (String[] bad :
        new String[][] {{"size", "0"}, {"size", "101"}, {"page", "-1"}, {"status", "NOPE"}}) {
      call(get("/users").param(bad[0], bad[1]), admin, null).andExpect(status().isBadRequest());
    }
    call(get("/users").param("keyword", "x".repeat(51)), admin, null)
        .andExpect(status().isBadRequest());
  }

  @Test
  void patchingYourOwnRoleIsRejectedButOtherFieldsAreAccepted() throws Exception {
    String me = "/users/" + admin.userId();
    long version =
        ((Number)
                JsonPath.read(
                    call(get(me), admin, null).andReturn().getResponse().getContentAsString(),
                    "$.version"))
            .longValue();
    call(
            patch(me),
            admin,
            "{\"displayName\":\"Me\",\"role\":\"MEMBER\",\"version\":" + version + "}")
        .andExpect(status().isConflict())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("CANNOT_CHANGE_OWN_ROLE"));
    call(
            patch(me),
            admin,
            "{\"displayName\":\"Renamed Me\",\"role\":\"TENANT_ADMIN\",\"version\":"
                + version
                + "}")
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.displayName").value("Renamed Me"));
  }

  @Test
  void unlockAndResetPasswordMatchTheContract() throws Exception {
    String id = createUser(TestAccounts.uniqueName());
    call(post("/users/" + id + "/unlock"), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.locked").value(false));
    call(post("/users/" + id + "/reset-password"), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
        .andExpect(jsonPath("$.user.mustChangePassword").value(true));
    call(
            post("/users/" + id + "/reset-password"),
            admin,
            "{\"temporaryPassword\":\"Giv3n-Passw0rd!\"}")
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.temporaryPassword").doesNotExist());
    call(post("/users/" + id + "/reset-password"), admin, "{\"temporaryPassword\":\"weak\"}")
        .andExpect(status().isBadRequest());
    call(post("/users/" + Long.MAX_VALUE + "/unlock"), admin, null)
        .andExpect(status().isNotFound())
        .andExpect(valid());
    call(post("/users/" + admin.userId() + "/reset-password"), admin, null)
        .andExpect(status().isOk());
  }

  @Test
  void aLockedUserIsReportedAsLockedUntilUnlocked() throws Exception {
    String id = createUser(TestAccounts.uniqueName());
    jdbcOwner()
        .update(
            "update app_user set locked_until = now() + interval '10 minutes' where id = ?",
            Long.valueOf(id));
    call(get("/users/" + id), admin, null)
        .andExpect(jsonPath("$.locked").value(true))
        .andExpect(jsonPath("$.lockedUntil").isNotEmpty());
    call(post("/users/" + id + "/unlock"), admin, null)
        .andExpect(jsonPath("$.locked").value(false))
        .andExpect(jsonPath("$.lockedUntil").doesNotExist());
  }

  private org.springframework.jdbc.core.JdbcTemplate jdbcOwner() {
    return new org.springframework.jdbc.core.JdbcTemplate(ownerDataSource());
  }

  @Test
  void getPatchDisableAndEnable() throws Exception {
    String id = createUser(TestAccounts.uniqueName());
    long version =
        ((Number)
                JsonPath.read(
                    call(get("/users/" + id), admin, null)
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                    "$.version"))
            .longValue();

    call(
            patch("/users/" + id),
            admin,
            "{\"displayName\":\"New Name\",\"role\":\"MEMBER\",\"version\":" + version + "}")
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.displayName").value("New Name"));
    call(
            patch("/users/" + id),
            admin,
            "{\"displayName\":\"Stale\",\"role\":\"MEMBER\",\"version\":" + version + "}")
        .andExpect(status().isConflict())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    call(post("/users/" + id + "/disable"), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.status").value("DISABLED"));
    call(post("/users/" + id + "/enable"), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    call(post("/users/" + admin.userId() + "/disable"), admin, null)
        .andExpect(status().isConflict())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("CANNOT_DISABLE_SELF"));
    call(get("/users/" + Long.MAX_VALUE), admin, null)
        .andExpect(status().isNotFound())
        .andExpect(valid());
  }

  @Test
  void membersAreForbiddenAndForcedPasswordChangeBlocksTheApi() throws Exception {
    var member =
        sessions.open(0, fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false));
    call(get("/users"), member, null)
        .andExpect(status().isForbidden())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    var forced =
        sessions.open(0, fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, true));
    call(get("/users"), forced, null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
  }
}
