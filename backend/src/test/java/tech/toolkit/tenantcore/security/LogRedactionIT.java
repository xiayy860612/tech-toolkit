package tech.toolkit.tenantcore.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestJwt;
import tech.toolkit.tenantcore.support.TestSessions;

/** FR-006：登录、改密、创建用户、重置密码之后，日志与审计 detail 都不含密码、会话令牌或 JWT。 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
      "logging.level.tech.toolkit=DEBUG",
      "logging.level.org.springframework.web=DEBUG"
    })
class LogRedactionIT extends AbstractPostgresIT {

  private static final String PW = "Str0ng-Passw0rd";
  private static final String NEW_PW = "N3w-Secret-Passw0rd";
  private static final String SUPPLIED = "Supp1ied-Secret-Pw!";

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

  private MockHttpServletRequestBuilder withJwt(MockHttpServletRequestBuilder b, String jwt) {
    return b.header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON);
  }

  @Test
  void noSecretEverReachesTheLogsOrAuditDetail(CapturedOutput output) throws Exception {
    var admin = fixture.create(0, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    String serviceJwt = TestJwt.service(0).sign();
    List<String> secrets = new ArrayList<>(List.of(PW, NEW_PW, SUPPLIED));

    // 登录
    String login =
        mvc.perform(
                withJwt(post("/auth/login"), serviceJwt)
                    .content(
                        "{\"username\":\"" + admin.username() + "\",\"password\":\"" + PW + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    secrets.add(JsonPath.read(login, "$.sessionToken"));
    mvc.perform(
            withJwt(post("/auth/login"), serviceJwt)
                .content(
                    "{\"username\":\"" + admin.username() + "\",\"password\":\"Wr0ng-Guess-Pw!\"}"))
        .andExpect(status().isUnauthorized());
    secrets.add("Wr0ng-Guess-Pw!");

    // 改密、创建用户、重置密码
    var open = sessions.open(0, admin);
    secrets.add(open.jwt());
    mvc.perform(
            withJwt(post("/auth/change-password"), open.jwt())
                .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + NEW_PW + "\"}"))
        .andExpect(status().isNoContent());
    String name = TestAccounts.uniqueName();
    String created =
        mvc.perform(
                withJwt(post("/users"), open.jwt())
                    .content(
                        "{\"displayName\":\"Redact\",\"role\":\"MEMBER\",\"username\":\""
                            + name
                            + "\",\"temporaryPassword\":\""
                            + SUPPLIED
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long userId = ((Number) JsonPath.read(created, "$.user.id")).longValue();
    String reset =
        mvc.perform(withJwt(post("/users/" + userId + "/reset-password"), open.jwt()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    secrets.add(JsonPath.read(reset, "$.temporaryPassword"));

    String logs = output.getAll();
    String audit =
        owner
            .queryForList(
                "select detail::text from audit_log where detail is not null", String.class)
            .toString();
    for (String secret : secrets) {
      assertThat(logs).as("logs must not contain a secret").doesNotContain(secret);
      assertThat(audit).as("audit detail must not contain a secret").doesNotContain(secret);
    }
  }
}
