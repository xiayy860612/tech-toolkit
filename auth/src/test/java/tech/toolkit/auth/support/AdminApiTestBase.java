package tech.toolkit.auth.support;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tech.toolkit.auth.bootstrap.BootstrapService;
import tools.jackson.databind.JsonNode;

/**
 * Base class for tests of the admin API: a bootstrapped administrator who has logged in to the
 * {@code auth-admin} client and holds an access token with the {@code auth.admin} scope.
 */
public abstract class AdminApiTestBase extends OAuthTestBase {

  protected static final String ADMIN = "root";
  protected static final String ADMIN_PASSWORD = "Admin-Passw0rd-12345!";
  protected static final String ADMIN_REDIRECT = "http://127.0.0.1:8765/callback";

  @Autowired protected BootstrapService bootstrap;

  protected String adminToken;

  @BeforeEach
  void anAdministratorWithAToken() throws Exception {
    bootstrap.run(ADMIN, ADMIN_PASSWORD, List.of(ADMIN_REDIRECT));
    ownerJdbc()
        .update("update auth_user set must_change_password = false where username = ?", ADMIN);
    adminToken = tokenOf(ADMIN, ADMIN_PASSWORD, "openid auth.admin");
  }

  /** Logs in to the admin tools' client and returns the access token. */
  protected String tokenOf(String username, String password, String scope) throws Exception {
    String verifier = newVerifier();
    CodeResult granted =
        obtainCode(
            new Browser(),
            BootstrapService.ADMIN_CLIENT_ID,
            ADMIN_REDIRECT,
            challengeOf(verifier),
            scope,
            username,
            password);
    MvcResult response =
        exchangePublic(BootstrapService.ADMIN_CLIENT_ID, granted.code(), ADMIN_REDIRECT, verifier);
    JsonNode body = JSON.readTree(response.getResponse().getContentAsString());
    if (!body.has("access_token")) {
      throw new IllegalStateException(
          "no token for " + username + ": " + response.getResponse().getStatus());
    }
    return body.get("access_token").asString();
  }

  protected MockHttpServletRequestBuilder withToken(
      MockHttpServletRequestBuilder request, String token) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
  }

  protected MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(withToken(request, adminToken)).andReturn();
  }

  protected JsonNode json(MvcResult result) throws Exception {
    return JSON.readTree(result.getResponse().getContentAsString());
  }

  protected long idOf(String username) {
    return ownerJdbc()
        .queryForObject("select id from auth_user where username = ?", Long.class, username);
  }
}
