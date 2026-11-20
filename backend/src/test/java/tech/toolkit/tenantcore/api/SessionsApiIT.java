package tech.toolkit.tenantcore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tech.toolkit.tenantcore.support.OpenApiContract.valid;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestSessions;

@AutoConfigureMockMvc
class SessionsApiIT extends AbstractPostgresIT {

  private static final String PW = "Str0ng-Passw0rd";
  private static final String CHROME_MAC =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/130.0.0.0 Safari/537.36";

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
  private TestSessions.Open admin;

  @BeforeEach
  void setUp() {
    admin =
        sessions.open(
            0,
            fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false),
            CHROME_MAC);
  }

  private ResultActions call(MockHttpServletRequestBuilder b, TestSessions.Open who)
      throws Exception {
    return mvc.perform(b.header("Authorization", who.bearer()));
  }

  private TestSessions.Open memberSession(String username) {
    return sessions.open(0, fixture.create(0, username, PW, Role.MEMBER, false), CHROME_MAC);
  }

  private int audits(String action, Long target) {
    return owner.queryForObject(
        "select count(*) from audit_log where action = ? and target_id = ?",
        Integer.class,
        action,
        target);
  }

  @Test
  void listsOnlyValidSessionsOfTheTenantFilteredByKeywordAndMarksTheCurrentOne() throws Exception {
    String name = "sess" + TestAccounts.uniqueName();
    var member = memberSession(name);
    var revoked = memberSession("rev" + TestAccounts.uniqueName());
    owner.update(
        "update session set revoked_at = now(), revoke_reason='LOGOUT' where id = ?",
        revoked.sessionId());

    String all =
        call(get("/sessions").param("size", "100"), admin)
            .andExpect(status().isOk())
            .andExpect(valid())
            .andExpect(
                jsonPath("$.items[?(@.current == true)].id").value(admin.sessionId().toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(all)
        .contains(member.sessionId().toString())
        .doesNotContain(revoked.sessionId().toString())
        .doesNotContain("tokenHash")
        .doesNotContain("token")
        .doesNotContain("Mozilla")
        .doesNotContain("userAgent");

    call(get("/sessions").param("keyword", name), admin)
        .andExpect(valid())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].username").value(name))
        .andExpect(jsonPath("$.items[0].device").value("Chrome 130 / macOS"))
        .andExpect(jsonPath("$.items[0].ip").value("203.0.113.5"))
        .andExpect(jsonPath("$.items[0].current").value(false));
    call(get("/sessions").param("keyword", "Test " + name), admin)
        .andExpect(jsonPath("$.total").value(1));
    call(get("/sessions").param("size", "0"), admin).andExpect(status().isBadRequest());
  }

  @Test
  void membersCannotListOrRevoke() throws Exception {
    var member = memberSession(TestAccounts.uniqueName());
    call(get("/sessions"), member).andExpect(status().isForbidden()).andExpect(valid());
    call(delete("/sessions/" + UUID.randomUUID()), member).andExpect(status().isForbidden());
    call(delete("/users/" + admin.userId() + "/sessions"), member)
        .andExpect(status().isForbidden());
  }

  @Test
  void revokingASessionLogsItOutAndAuditsAndIsIdempotent() throws Exception {
    var member = memberSession(TestAccounts.uniqueName());
    call(get("/auth/me"), member).andExpect(status().isOk());

    call(delete("/sessions/" + member.sessionId()), admin)
        .andExpect(status().isNoContent())
        .andExpect(valid());

    call(get("/auth/me"), member).andExpect(status().isUnauthorized());
    assertThat(
            owner.queryForObject(
                "select revoke_reason from session where id = ?", String.class, member.sessionId()))
        .isEqualTo("ADMIN_REVOKED");
    assertThat(audits("SESSION_REVOKED", member.userId())).isEqualTo(1);
    assertThat(
            owner.queryForObject(
                "select detail::text from audit_log where action='SESSION_REVOKED' and target_id = ?",
                String.class,
                member.userId()))
        .doesNotContain(member.sessionId().toString());

    call(delete("/sessions/" + member.sessionId()), admin).andExpect(status().isNoContent());
    call(delete("/sessions/" + UUID.randomUUID()), admin).andExpect(status().isNoContent());
    assertThat(audits("SESSION_REVOKED", member.userId())).isEqualTo(1);
  }

  @Test
  void theCurrentSessionCannotBeRevoked() throws Exception {
    call(delete("/sessions/" + admin.sessionId()), admin)
        .andExpect(status().isConflict())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("CANNOT_REVOKE_CURRENT_SESSION"));
    call(get("/auth/me"), admin).andExpect(status().isOk());
  }

  @Test
  void revokingAllSessionsOfAUser() throws Exception {
    var acc = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var one = sessions.open(0, acc);
    var two = sessions.open(0, acc);

    call(delete("/users/" + acc.user().getId() + "/sessions"), admin)
        .andExpect(status().isNoContent())
        .andExpect(valid());

    call(get("/auth/me"), one).andExpect(status().isUnauthorized());
    call(get("/auth/me"), two).andExpect(status().isUnauthorized());
    assertThat(
            owner.queryForObject(
                "select detail->>'count' from audit_log where action='SESSIONS_REVOKED_ALL' and target_id = ?",
                String.class,
                acc.user().getId()))
        .isEqualTo("2");
  }

  @Test
  void revokingYourOwnSessionsOrUnknownUsersIsRejected() throws Exception {
    call(delete("/users/" + admin.userId() + "/sessions"), admin)
        .andExpect(status().isConflict())
        .andExpect(valid())
        .andExpect(jsonPath("$.code").value("CANNOT_REVOKE_OWN_SESSIONS"));
    call(delete("/users/" + Long.MAX_VALUE + "/sessions"), admin)
        .andExpect(status().isNotFound())
        .andExpect(valid());
    call(get("/auth/me"), admin).andExpect(content().contentTypeCompatibleWith("application/json"));
  }
}
