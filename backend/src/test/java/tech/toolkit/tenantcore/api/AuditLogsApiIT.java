package tech.toolkit.tenantcore.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tech.toolkit.tenantcore.support.OpenApiContract.valid;

import java.time.Instant;
import org.hamcrest.Matchers;
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
class AuditLogsApiIT extends AbstractPostgresIT {

  private static final String PW = "Str0ng-Passw0rd";

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;

  private TestSessions.Open admin;

  @BeforeEach
  void setUp() {
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

  @Test
  void recordedAdminActionsAreQueryableWithActorTargetResultAndIp() throws Exception {
    String name = TestAccounts.uniqueName();
    call(
            post("/users"),
            admin,
            "{\"displayName\":\"Audit Me\",\"role\":\"MEMBER\",\"username\":\"" + name + "\"}")
        .andExpect(status().isCreated());

    call(
            get("/audit-logs")
                .param("action", "USER_CREATED")
                .param("actorUserId", admin.userId().toString()),
            admin,
            null)
        .andExpect(status().isOk())
        .andExpect(valid())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].action").value("USER_CREATED"))
        .andExpect(jsonPath("$.items[0].actorUserId").value(admin.userId().toString()))
        .andExpect(jsonPath("$.items[0].targetType").value("USER"))
        .andExpect(jsonPath("$.items[0].result").value("SUCCESS"))
        .andExpect(jsonPath("$.items[0].ip").value("203.0.113.5"));
  }

  @Test
  void timeRangeAndPagingFilter() throws Exception {
    String past = Instant.now().minusSeconds(3600).toString();
    String future = Instant.now().plusSeconds(3600).toString();
    call(get("/audit-logs").param("from", future), admin, null)
        .andExpect(valid())
        .andExpect(jsonPath("$.total").value(0));
    call(get("/audit-logs").param("from", past).param("to", future).param("size", "1"), admin, null)
        .andExpect(valid())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.size").value(1));
    call(get("/audit-logs").param("action", "NOT_AN_ACTION"), admin, null)
        .andExpect(jsonPath("$.total").value(0));
    call(get("/audit-logs").param("page", "-1"), admin, null).andExpect(status().isBadRequest());
  }

  @Test
  void onlyAdministratorsMayReadAndTheResponseNeverCarriesDetail() throws Exception {
    var member =
        sessions.open(0, fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false));
    call(get("/audit-logs"), member, null).andExpect(status().isForbidden()).andExpect(valid());
    call(get("/audit-logs"), admin, null)
        .andExpect(content().string(Matchers.not(Matchers.containsString("\"detail\""))));
  }
}
