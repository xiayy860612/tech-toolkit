package tech.toolkit.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.AdminApiTestBase;
import tools.jackson.databind.JsonNode;

/**
 * SC-005, FR-028: after a run through everything that handles a secret (logins good and bad, code
 * exchange, refresh and its reuse, revocation, user and client administration), neither the log nor
 * the audit trail holds a password, a password hash, a token, a code or a client secret.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneIT extends AdminApiTestBase {

  private static final String WRONG_PASSWORD = "Wrong-Passw0rd-1";
  private static final String BOB_PASSWORD = "Initial-Passw0rd-1";
  private static final String BOB_RESET = "Temporary-Passw0rd-1";

  private MvcResult postJson(String path, String body) throws Exception {
    return call(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void noSecretReachesTheLogOrTheAuditTrail(CapturedOutput output) throws Exception {
    List<String> secrets = new ArrayList<>(List.of(PASSWORD, WRONG_PASSWORD, ADMIN_PASSWORD));

    // a failed login, then a good one with the full code flow
    signIn(new Browser(), USERNAME, WRONG_PASSWORD);
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));
    JsonNode tokens = json(exchange(CLIENT1, SECRET1, granted.code(), REDIRECT1, verifier));
    secrets.addAll(List.of(granted.code(), SECRET1, SECRET2));
    for (String name : List.of("access_token", "refresh_token", "id_token")) {
      secrets.add(tokens.get(name).asString());
    }
    // refresh, then present the old refresh token again (reuse detection), then revoke
    JsonNode refreshed = json(refresh(CLIENT1, SECRET1, tokens.get("refresh_token").asString()));
    secrets.add(refreshed.get("access_token").asString());
    secrets.add(refreshed.get("refresh_token").asString());
    refresh(CLIENT1, SECRET1, tokens.get("refresh_token").asString());
    revoke(CLIENT1, SECRET1, refreshed.get("refresh_token").asString(), "refresh_token");

    // administration: a user with a password and a reset, a client with a secret and a rotation
    postJson(
        "/admin/v1/users",
        "{\"username\":\"bob\",\"initialPassword\":\"%s\"}".formatted(BOB_PASSWORD));
    secrets.add(BOB_PASSWORD);
    long bob = idOf("bob");
    postJson(
        "/admin/v1/users/" + bob + "/password-reset",
        "{\"temporaryPassword\":\"%s\"}".formatted(BOB_RESET));
    secrets.add(BOB_RESET);
    JsonNode created =
        json(
            postJson(
                "/admin/v1/clients",
                """
                {"clientId":"client4","type":"CONFIDENTIAL",
                 "redirectUris":["http://127.0.0.1:8084/callback"],"scopes":["openid"]}"""));
    secrets.add(created.get("clientSecret").asString());
    secrets.add(
        json(call(post("/admin/v1/clients/client4/secret-rotation")))
            .get("clientSecret")
            .asString());
    secrets.add(adminToken);
    ownerJdbc()
        .queryForList("select password_hash from auth_user", String.class)
        .forEach(secrets::add);
    ownerJdbc()
        .queryForList(
            "select client_secret from oauth2_registered_client where client_secret is not null",
            String.class)
        .forEach(secrets::add);

    String audit =
        ownerJdbc()
            .queryForObject(
                "select string_agg(concat_ws('|', type, actor, client_id, source_ip, detail::text),"
                    + " E'\\n') from auth_audit_event",
                String.class);

    assertThat(audit).as("the audit trail is not empty").contains("LOGIN_FAILURE");
    assertThat(output.getAll()).as("the log was captured").contains("audit type=LOGIN_FAILURE");
    for (String secret : secrets) {
      assertThat(secret).isNotBlank();
      assertThat(output.getAll()).as("log holds a secret").doesNotContain(secret);
      assertThat(audit).as("audit holds a secret").doesNotContain(secret);
    }
    assertThat(output.getAll()).doesNotContain("argon2");
  }
}
