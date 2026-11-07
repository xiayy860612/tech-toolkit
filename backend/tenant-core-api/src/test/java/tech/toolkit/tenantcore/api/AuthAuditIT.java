package tech.toolkit.tenantcore.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuthAuditIT extends ApiTestBase {

  @Test
  void loginPasswordChangeExchangeAndLogoutAreAuditedWithoutSecrets() {
    bootstrap(core(), "acme", "admin1");
    login("acme", "admin1", "wrong password 1234");
    String initial = login("acme", "admin1", PASSWORD).json().get("token").asText();
    String session =
        call(
                HttpMethod.POST,
                "/v1/auth/change-password",
                session(initial),
                Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))
            .json()
            .get("token")
            .asText();
    String jwt = exchange(session);
    call(HttpMethod.POST, "/v1/auth/logout", session(session), null);

    assertThat(auditOutcomes("login.failure")).containsExactly("FAILURE");
    assertThat(auditOutcomes("login.success")).containsExactly("SUCCESS");
    assertThat(auditOutcomes("password.change")).containsExactly("SUCCESS");
    assertThat(auditOutcomes("token.exchange")).containsExactly("SUCCESS");
    assertThat(auditOutcomes("logout")).containsExactly("SUCCESS");

    List<String> rows =
        JdbcClient.create(ownerDataSource())
            .sql("SELECT actor || ' ' || action || ' ' || target FROM audit_event")
            .query(String.class)
            .list();
    String everything = String.join("\n", rows);
    assertThat(everything)
        .doesNotContain(PASSWORD)
        .doesNotContain(NEW_PASSWORD)
        .doesNotContain(initial)
        .doesNotContain(session)
        .doesNotContain(jwt);
  }
}
