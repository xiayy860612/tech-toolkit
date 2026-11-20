package tech.toolkit.tenantcore.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tech.toolkit.tenantcore.audit.AuditEvent;
import tech.toolkit.tenantcore.audit.AuditService;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.identity.UserService;
import tech.toolkit.tenantcore.platform.Actor;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestSessions;

/** 各类撤销在同一事务内完成：revoke_reason 正确、下一次请求 401、撤销与审计同生共死。 */
@AutoConfigureMockMvc
class SessionRevocationIT extends AbstractPostgresIT {

  private static final String PW = "Str0ng-Passw0rd";

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;
  @Autowired UserService users;
  @MockitoSpyBean AuditService audit;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

  private Actor adminActor() {
    var admin = fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    return new Actor(admin.user().getId(), null);
  }

  private String reason(TestSessions.Open s) {
    return owner.queryForObject(
        "select revoke_reason from session where id = ?", String.class, s.sessionId());
  }

  private void assertRejected(TestSessions.Open s) throws Exception {
    mvc.perform(get("/auth/me").header("Authorization", s.bearer()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void userDisabledAndPasswordResetEachRevokeWithTheirReason() throws Exception {
    Actor actor = adminActor();

    var u = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var su = sessions.open(0, u);
    TenantContext.runAs(0L, () -> users.disable(actor, u.user().getId()));
    assertThat(reason(su)).isEqualTo("USER_DISABLED");
    assertRejected(su);

    var r = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var sr = sessions.open(0, r);
    TenantContext.runAs(0L, () -> users.resetPassword(actor, r.user().getId(), null));
    assertThat(reason(sr)).isEqualTo("PASSWORD_RESET");
    assertRejected(sr);
  }

  @Test
  void changingThePasswordKeepsTheCurrentSessionAndLogoutRevokesIt() throws Exception {
    var acc = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var current = sessions.open(0, acc);
    var other = sessions.open(0, acc);

    mvc.perform(
            post("/auth/change-password")
                .header("Authorization", current.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"N3w-Passw0rd-Longer\"}"))
        .andExpect(status().isNoContent());
    assertThat(reason(other)).isEqualTo("PASSWORD_CHANGED");
    assertRejected(other);
    mvc.perform(get("/auth/me").header("Authorization", current.bearer()))
        .andExpect(status().isOk());

    mvc.perform(post("/auth/logout").header("Authorization", current.bearer()))
        .andExpect(status().isNoContent());
    assertThat(reason(current)).isEqualTo("LOGOUT");
    assertRejected(current);
  }

  @Test
  void revocationAndAuditSucceedOrFailTogether() {
    Actor actor = adminActor();
    var u = fixture.create(0, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var s = sessions.open(0, u);
    doThrow(new IllegalStateException("audit store down"))
        .when(audit)
        .record(any(AuditEvent.class));

    assertThatThrownBy(() -> TenantContext.runAs(0L, () -> users.disable(actor, u.user().getId())))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            owner.queryForObject(
                "select revoked_at is null from session where id = ?",
                Boolean.class,
                s.sessionId()))
        .isTrue();
    assertThat(
            owner.queryForObject(
                "select status from app_user where id = ?", String.class, u.user().getId()))
        .isEqualTo("ACTIVE");
  }
}
