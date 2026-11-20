package tech.toolkit.tenantcore.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.identity.AppUser;
import tech.toolkit.tenantcore.identity.AppUserRepository;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestSessions;

/** 跨租户隔离测试集（SC-003）：本期租户固定为 0，用夹具租户 1 作为"另一个租户"。 */
@AutoConfigureMockMvc
class TenantIsolationIT extends AbstractPostgresIT {

  static final String PW = "Str0ng-Passw0rd";
  static final long A = 0L;
  static final long B = 1L;

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;
  @Autowired AppUserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  @Test
  void withoutTenantContextNoRowIsVisible() {
    fixture.create(A, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    fixture.create(B, TestAccounts.uniqueName(), PW, Role.MEMBER, false);

    Integer visible =
        tx.execute(s -> jdbc.queryForObject("select count(*) from app_user", Integer.class));

    assertThat(visible).isZero();
    List<AppUser> visibleToA = TenantContext.callAs(A, () -> tx.execute(s -> users.findAll()));
    assertThat(visibleToA).isNotEmpty();
  }

  @Test
  void tenantAContextCannotReadOrWriteTenantBRows() {
    var inA = fixture.create(A, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var inB = fixture.create(B, TestAccounts.uniqueName(), PW, Role.MEMBER, false);

    List<AppUser> visibleToA = TenantContext.callAs(A, () -> tx.execute(s -> users.findAll()));
    assertThat(visibleToA).extracting(u -> u.getId()).contains(inA.user().getId());
    assertThat(visibleToA).extracting(u -> u.getId()).doesNotContain(inB.user().getId());
    Optional<AppUser> foreign =
        TenantContext.callAs(A, () -> tx.execute(s -> users.findById(inB.user().getId())));
    assertThat(foreign).isEmpty();

    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    A,
                    () ->
                        tx.executeWithoutResult(
                            s ->
                                jdbc.update(
                                    "insert into app_user(id, tenant_id, display_name, role)"
                                        + " values (?, ?, 'x', 'MEMBER')",
                                    UUID.randomUUID(),
                                    B))))
        .isInstanceOf(DataAccessException.class);
    Integer updated =
        TenantContext.callAs(
            A,
            () ->
                tx.execute(
                    s ->
                        jdbc.update(
                            "update app_user set display_name = 'hacked' where id = ?",
                            inB.user().getId())));
    assertThat(updated).isZero();
  }

  @Test
  void tenantIdentifiersInHeadersParametersOrBodyNeverChangeTheTenant() throws Exception {
    var inA = fixture.create(A, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    fixture.create(B, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    var open = sessions.open(A, inA);

    mvc.perform(
            get("/auth/me")
                .param("tenantId", "1")
                .header("X-Tenant-Id", "1")
                .header("X-Tenant", "1")
                .header("Authorization", open.bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(0))
        .andExpect(jsonPath("$.userId").value(inA.user().getId().toString()));
  }

  @Test
  void aJwtWithATamperedTenantIdFailsSignatureVerification() throws Exception {
    var inA = fixture.create(A, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    var open = sessions.open(A, inA);
    String[] parts = open.jwt().split("\\.");
    String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    assertThat(payload).contains("\"tenant_id\":0");
    String forged =
        parts[0]
            + "."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    payload
                        .replace("\"tenant_id\":0", "\"tenant_id\":1")
                        .getBytes(StandardCharsets.UTF_8))
            + "."
            + parts[2];

    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + forged))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void meOnlyReturnsTheCaller() throws Exception {
    var first = fixture.create(A, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var second = fixture.create(A, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    var openSecond = sessions.open(A, second);

    mvc.perform(get("/auth/me").header("Authorization", openSecond.bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(second.user().getId().toString()))
        .andExpect(jsonPath("$.accountId").value(second.account().getId().toString()));
    assertThat(first.user().getId()).isNotEqualTo(second.user().getId());
  }
}
