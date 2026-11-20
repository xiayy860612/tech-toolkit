package tech.toolkit.tenantcore.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestSessions;

/** /auth/me 的 role 取自数据库当前值，不信任令牌（令牌里本来就没有 role）。 */
@AutoConfigureMockMvc
class MeRoleFreshnessIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;

  @Test
  void aDemotedAdministratorImmediatelyReadsAsMemberAndLosesAdminEndpoints() throws Exception {
    var acc =
        fixture.create(0, TestAccounts.uniqueName(), "Str0ng-Passw0rd", Role.TENANT_ADMIN, false);
    var open = sessions.open(0, acc);
    mvc.perform(get("/auth/me").header("Authorization", open.bearer()))
        .andExpect(jsonPath("$.role").value("TENANT_ADMIN"));
    mvc.perform(get("/sessions").header("Authorization", open.bearer())).andExpect(status().isOk());

    new JdbcTemplate(ownerDataSource())
        .update("update app_user set role = 'MEMBER' where id = ?", acc.user().getId());

    mvc.perform(get("/auth/me").header("Authorization", open.bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("MEMBER"));
    mvc.perform(get("/sessions").header("Authorization", open.bearer()))
        .andExpect(status().isForbidden());
  }
}
