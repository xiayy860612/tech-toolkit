package tech.toolkit.tenantcore.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class AccountsApiContractIT extends AbstractPostgresIT {

  private static final String PW = "Str0ng-Passw0rd";

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;

  private TestSessions.Open admin;
  private TestAccounts.Created target;
  private String base;

  @BeforeEach
  void setUp() {
    admin =
        sessions.open(
            0, fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false));
    target = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    base = "/users/" + target.user().getId() + "/accounts";
  }

  private ResultActions call(MockHttpServletRequestBuilder b, TestSessions.Open who, String body)
      throws Exception {
    b.header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON);
    if (body != null) {
      b.content(body);
    }
    return mvc.perform(b);
  }

  private String addAccount(String username) throws Exception {
    String json =
        call(
                post(base),
                admin,
                "{\"type\":\"USERNAME_PASSWORD\",\"username\":\"" + username + "\"}")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  @Test
  void listAndCreateMatchTheContract() throws Exception {
    call(get(base), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].identifier").value(target.username()));
    call(
            post(base),
            admin,
            "{\"type\":\"USERNAME_PASSWORD\",\"username\":\"" + TestAccounts.uniqueName() + "\"}")
        .andExpect(status().isCreated())
        .andExpect(valid())
        .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
        .andExpect(jsonPath("$.mustChangePassword").value(true));
    call(
            post(base),
            admin,
            "{\"type\":\"USERNAME_PASSWORD\",\"username\":\""
                + target.username().toUpperCase()
                + "\"}")
        .andExpect(status().isConflict())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    call(post(base), admin, "{\"type\":\"USERNAME_PASSWORD\",\"username\":\"!\"}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void disableEnableUnlockAndTheConfirmParameter() throws Exception {
    String only = target.account().getId().toString();
    call(post(base + "/" + only + "/disable"), admin, null)
        .andExpect(status().isConflict())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("LAST_ACTIVE_ACCOUNT_CONFIRM_REQUIRED"));
    call(post(base + "/" + only + "/disable").param("confirm", "true"), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.status").value("DISABLED"));
    call(post(base + "/" + only + "/enable"), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    call(post(base + "/" + only + "/unlock"), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.locked").value(false));
  }

  @Test
  void resetPasswordWithAndWithoutABody() throws Exception {
    String id = target.account().getId().toString();
    call(post(base + "/" + id + "/reset-password"), admin, null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
    call(
            post(base + "/" + id + "/reset-password"),
            admin,
            "{\"temporaryPassword\":\"Giv3n-Passw0rd!\"}")
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.temporaryPassword").doesNotExist());
    call(post(base + "/" + id + "/reset-password"), admin, "{\"temporaryPassword\":\"weak\"}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteHonoursConfirmAndIsNotFoundForUnknownAccounts() throws Exception {
    String only = target.account().getId().toString();
    call(delete(base + "/" + only), admin, null)
        .andExpect(status().isConflict())
        .andExpect(valid());
    String extra = addAccount(TestAccounts.uniqueName());
    call(delete(base + "/" + extra), admin, null)
        .andExpect(status().isNoContent())
        .andExpect(valid());
    call(delete(base + "/" + only).param("confirm", "true"), admin, null)
        .andExpect(status().isNoContent())
        .andExpect(valid());
    call(delete(base + "/" + java.util.UUID.randomUUID()), admin, null)
        .andExpect(status().isNotFound())
        .andExpect(valid());
    call(get("/users/" + java.util.UUID.randomUUID() + "/accounts"), admin, null)
        .andExpect(status().isNotFound());
  }

  @Test
  void membersAreForbidden() throws Exception {
    var member =
        sessions.open(0, fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false));
    call(get(base), member, null).andExpect(status().isForbidden()).andExpect(valid());
    call(post(base + "/" + target.account().getId() + "/unlock"), member, null)
        .andExpect(status().isForbidden());
  }
}
