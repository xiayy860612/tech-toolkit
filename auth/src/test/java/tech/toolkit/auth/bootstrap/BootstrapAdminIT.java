package tech.toolkit.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import tech.toolkit.auth.support.PostgresTestBase;

/** The one-time setup of a new deployment: the first administrator and the admin tools' client. */
class BootstrapAdminIT extends PostgresTestBase {

  private static final String PASSWORD = "Initial-Admin-Passw0rd!";
  private static final List<String> REDIRECTS = List.of("http://127.0.0.1:8765/callback");

  @Autowired BootstrapService bootstrap;
  @Autowired RegisteredClientRepository clients;

  private Map<String, Object> adminRow() {
    return ownerJdbc()
        .queryForMap(
            "select username, is_admin, must_change_password, password_hash, status from auth_user"
                + " where username = 'root'");
  }

  @Test
  void createsTheFirstAdministratorWhoMustChangeThePassword() {
    BootstrapService.Result result = bootstrap.run("Root", PASSWORD, REDIRECTS);

    assertThat(result.adminCreated()).isTrue();
    Map<String, Object> row = adminRow();
    assertThat(row)
        .containsEntry("is_admin", true)
        .containsEntry("must_change_password", true)
        .containsEntry("status", "ENABLED");
    assertThat((String) row.get("password_hash")).doesNotContain(PASSWORD).startsWith("{argon2");
    assertThat(
            ownerJdbc()
                .queryForList(
                    "select actor from auth_audit_event where type = 'USER_CREATED'", String.class))
        .containsExactly("bootstrap");
  }

  @Test
  void registersTheAdminClientAsAPublicPkceClientWithTheAdminScope() {
    bootstrap.run("root", PASSWORD, REDIRECTS);

    RegisteredClient client = clients.findByClientId("auth-admin");

    assertThat(client).isNotNull();
    assertThat(client.getClientAuthenticationMethods())
        .containsExactly(ClientAuthenticationMethod.NONE);
    assertThat(client.getAuthorizationGrantTypes())
        .containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
    assertThat(client.getScopes()).contains("openid", "auth.admin");
    assertThat(client.getRedirectUris()).containsExactlyElementsOf(REDIRECTS);
    assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
    assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
    assertThat(client.getClientSecret()).isNull();
  }

  @Test
  void runningItAgainChangesNothing() {
    bootstrap.run("root", PASSWORD, REDIRECTS);
    String hash = (String) adminRow().get("password_hash");

    BootstrapService.Result again = bootstrap.run("root", "Another-Passw0rd-Entirely!", REDIRECTS);

    assertThat(again.adminCreated()).isFalse();
    assertThat(again.clientRegistered()).isFalse();
    assertThat(adminRow().get("password_hash")).isEqualTo(hash);
    assertThat(ownerJdbc().queryForObject("select count(*) from auth_user", Integer.class))
        .isEqualTo(1);
    assertThat(
            ownerJdbc()
                .queryForObject("select count(*) from oauth2_registered_client", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void aWeakPasswordCreatesNothing() {
    assertThatThrownBy(() -> bootstrap.run("root", "short", REDIRECTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("至少 12 位");

    assertThat(ownerJdbc().queryForObject("select count(*) from auth_user", Integer.class))
        .isZero();
  }

  @Test
  void theRunnerReadsThePasswordFromStandardInput() throws Exception {
    BootstrapAdminRunner runner = new BootstrapAdminRunner(bootstrap, "root", REDIRECTS);

    runner.runWith(
        new DefaultApplicationArguments(),
        new ByteArrayInputStream((PASSWORD + "\n").getBytes(StandardCharsets.UTF_8)));

    assertThat(adminRow()).containsEntry("is_admin", true);
  }

  @Test
  void theRunnerRefusesAPasswordGivenAsAnArgument() {
    BootstrapAdminRunner runner = new BootstrapAdminRunner(bootstrap, "root", REDIRECTS);

    assertThatThrownBy(
            () ->
                runner.runWith(
                    new DefaultApplicationArguments("--password=" + PASSWORD),
                    new ByteArrayInputStream(new byte[0])))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("argument");

    assertThat(ownerJdbc().queryForObject("select count(*) from auth_user", Integer.class))
        .isZero();
  }

  @Test
  void theRunnerNeedsAPasswordOnStandardInput() {
    BootstrapAdminRunner runner = new BootstrapAdminRunner(bootstrap, "root", REDIRECTS);

    assertThatThrownBy(
            () ->
                runner.runWith(
                    new DefaultApplicationArguments(), new ByteArrayInputStream(new byte[0])))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
