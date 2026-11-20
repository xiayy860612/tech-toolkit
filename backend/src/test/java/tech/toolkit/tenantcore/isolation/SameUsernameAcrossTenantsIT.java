package tech.toolkit.tenantcore.isolation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestJwt;

/** US4-3 / FR-014：用户名只在租户内唯一，两个租户可以有同名用户名，各自登录进入各自租户。 */
@AutoConfigureMockMvc
class SameUsernameAcrossTenantsIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;

  private ResultActions login(long tenant, String username, String password) throws Exception {
    return mvc.perform(
        post("/auth/login")
            .header("Authorization", "Bearer " + TestJwt.service(tenant).sign())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
  }

  @Test
  void theSameUsernameLogsIntoEachOwnTenantWithoutConflict() throws Exception {
    String username = TestAccounts.uniqueName();
    var inA = fixture.create(0, username, "Passw0rd-in-tenant-A", Role.TENANT_ADMIN, false);
    var inB = fixture.create(1, username, "Passw0rd-in-tenant-B", Role.TENANT_ADMIN, false);

    login(0, username, "Passw0rd-in-tenant-A")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.me.tenantId").value(0))
        .andExpect(jsonPath("$.me.userId").value(inA.user().getId().toString()));
    login(1, username, "Passw0rd-in-tenant-B")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.me.tenantId").value(1))
        .andExpect(jsonPath("$.me.userId").value(inB.user().getId().toString()));
  }

  @Test
  void aPasswordOfOneTenantDoesNotOpenTheSameNamedAccountOfTheOther() throws Exception {
    String username = TestAccounts.uniqueName();
    fixture.create(0, username, "Passw0rd-in-tenant-A", Role.MEMBER, false);
    fixture.create(1, username, "Passw0rd-in-tenant-B", Role.MEMBER, false);

    login(0, username, "Passw0rd-in-tenant-B").andExpect(status().isUnauthorized());
    login(1, username, "Passw0rd-in-tenant-A").andExpect(status().isUnauthorized());
  }
}
