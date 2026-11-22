package tech.toolkit.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.toolkit.auth.support.OAuthTestBase;

class LoginAuditIT extends OAuthTestBase {

  private List<Map<String, Object>> events(String type) {
    return ownerJdbc().queryForList("select * from auth_audit_event where type = ?", type);
  }

  @Test
  void aFailedLoginIsAuditedWithoutThePassword() throws Exception {
    signIn(new Browser(), USERNAME, "this-is-not-the-password");

    List<Map<String, Object>> failures = events("LOGIN_FAILURE");
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).containsEntry("result", "FAILURE").containsEntry("actor", USERNAME);
    assertThat(failures.get(0).toString()).doesNotContain("this-is-not-the-password");
  }

  @Test
  void aSuccessfulLoginAndTheTokenIssuanceAreAudited() throws Exception {
    obtainTokens();

    List<Map<String, Object>> logins = events("LOGIN_SUCCESS");
    assertThat(logins).hasSize(1);
    assertThat(logins.get(0).get("subject_user_id")).isNotNull();
    List<Map<String, Object>> issued = events("TOKEN_ISSUED");
    assertThat(issued).hasSize(1);
    assertThat(issued.get(0)).containsEntry("client_id", CLIENT1);
    assertThat(ownerJdbc().queryForList("select detail::text from auth_audit_event", String.class))
        .noneMatch(d -> d.contains(PASSWORD) || d.contains("eyJ"));
  }
}
