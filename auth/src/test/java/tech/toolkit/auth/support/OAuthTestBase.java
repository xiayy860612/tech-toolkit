package tech.toolkit.auth.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import tech.toolkit.auth.client.DevClientSeeder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for tests that drive the OAuth 2.0 flow over MockMvc. Fixtures (user alice, clients
 * client1 and client2) come from the dev seeder, configured in application-test.yml, and are
 * re-created before every test because the base class empties the tables.
 */
@AutoConfigureMockMvc
public abstract class OAuthTestBase extends PostgresTestBase {

  protected static final String USERNAME = "alice";
  protected static final String PASSWORD = "Correct-Horse-Battery-9";

  protected static final String CLIENT1 = "client1";
  protected static final String SECRET1 = "client1-test-secret";
  protected static final String REDIRECT1 = "http://127.0.0.1:8081/callback";

  protected static final String CLIENT2 = "client2";
  protected static final String SECRET2 = "client2-test-secret";
  protected static final String REDIRECT2 = "http://127.0.0.1:8082/callback";

  protected static final String ISSUER = "http://localhost:9000";
  protected static final String ERROR_TEXT = "用户名或密码错误";
  protected static final String LOCK_HINT = "多次失败后账号将被临时锁定";

  protected static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired protected MockMvc mvc;
  @Autowired private DevClientSeeder seeder;

  @BeforeEach
  void seedFixtures() {
    seeder.seed();
  }

  /** A browser: keeps cookies between requests, like the real thing (and Spring Session needs). */
  protected final class Browser {

    private final Map<String, String> cookies = new LinkedHashMap<>();

    public Browser() {}

    public MvcResult get(String uri) throws Exception {
      return send(MockMvcRequestBuilders.get(URI.create(uri)).accept(MediaType.TEXT_HTML));
    }

    /** A form post with a valid CSRF token. */
    public MvcResult postForm(String uri, Map<String, String> fields) throws Exception {
      MockHttpServletRequestBuilder request = post(uri).with(csrf()).accept(MediaType.TEXT_HTML);
      fields.forEach(request::param);
      return send(request);
    }

    private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
      cookies.forEach((name, value) -> request.cookie(new Cookie(name, value)));
      MvcResult result = mvc.perform(request).andReturn();
      for (Cookie cookie : result.getResponse().getCookies()) {
        if (cookie.getMaxAge() == 0) {
          cookies.remove(cookie.getName());
        } else {
          cookies.put(cookie.getName(), cookie.getValue());
        }
      }
      return result;
    }
  }

  /** The outcome of the browser leg of the flow: the browser and the authorization code. */
  protected record CodeResult(Browser browser, String code, String redirectLocation) {}

  protected static String newVerifier() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  protected static String challengeOf(String verifier) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  protected static String authorizeUri(
      String clientId, String redirectUri, String state, String challenge) {
    return authorizeUri(clientId, redirectUri, state, challenge, "openid");
  }

  protected static String authorizeUri(
      String clientId, String redirectUri, String state, String challenge, String scope) {
    return UriComponentsBuilder.fromPath("/oauth2/authorize")
        .queryParam("response_type", "code")
        .queryParam("client_id", clientId)
        .queryParam("redirect_uri", redirectUri)
        .queryParam("scope", scope)
        .queryParam("state", state)
        .queryParam("nonce", "nonce-1")
        .queryParam("code_challenge", challenge)
        .queryParam("code_challenge_method", "S256")
        .build()
        // encoded here, and sent as a ready URI so that MockMvc does not encode it a second time
        // (a two-scope request would turn its %20 into %2520)
        .encode()
        .toUriString();
  }

  protected MvcResult authorize(Browser browser, String uri) throws Exception {
    return browser.get(uri);
  }

  /** Opens the login page, then submits the form, like a browser does. */
  protected MvcResult signIn(Browser browser, String username, String password) throws Exception {
    mvcStatusOk(browser.get("/login"));
    return browser.postForm("/login", Map.of("username", username, "password", password));
  }

  private void mvcStatusOk(MvcResult page) throws Exception {
    status().isOk().match(page);
  }

  /** authorize → login → authorize again, and returns the code from the final redirect. */
  protected CodeResult obtainCode(
      Browser browser, String clientId, String redirectUri, String challenge) throws Exception {
    return obtainCode(browser, clientId, redirectUri, challenge, "openid", USERNAME, PASSWORD);
  }

  protected CodeResult obtainCode(
      Browser browser,
      String clientId,
      String redirectUri,
      String challenge,
      String scope,
      String username,
      String password)
      throws Exception {
    String uri = authorizeUri(clientId, redirectUri, "state-1", challenge, scope);
    MvcResult first = authorize(browser, uri);
    String afterLogin = signIn(browser, username, password).getResponse().getRedirectedUrl();
    URI target = URI.create(afterLogin);
    String path =
        target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
    MvcResult granted = authorize(browser, path);
    String location = granted.getResponse().getRedirectedUrl();
    if (location == null) {
      throw new IllegalStateException(
          "the first authorization request "
              + uri
              + " answered "
              + first.getResponse().getStatus()
              + " "
              + first.getResponse().getRedirectedUrl()
              + " "
              + first.getResponse().getErrorMessage()
              + "; the authorization did not redirect: status "
              + granted.getResponse().getStatus()
              + ", "
              + granted.getResponse().getErrorMessage());
    }
    String code =
        UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
    return new CodeResult(browser, code, location);
  }

  /** The token request of a public client (no secret): it proves itself with the PKCE verifier. */
  protected MvcResult exchangePublic(
      String clientId, String code, String redirectUri, String verifier) throws Exception {
    return mvc.perform(
            post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", clientId)
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", redirectUri)
                .param("code_verifier", verifier))
        .andReturn();
  }

  protected MvcResult exchange(
      String clientId, String secret, String code, String redirectUri, String verifier)
      throws Exception {
    return mvc.perform(
            post("/oauth2/token")
                .with(httpBasic(clientId, secret))
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", redirectUri)
                .param("code_verifier", verifier))
        .andReturn();
  }

  protected MvcResult refresh(String clientId, String secret, String refreshToken)
      throws Exception {
    return mvc.perform(
            post("/oauth2/token")
                .with(httpBasic(clientId, secret))
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken))
        .andReturn();
  }

  protected MvcResult revoke(String clientId, String secret, String token, String hint)
      throws Exception {
    var request = post("/oauth2/revoke").param("token", token).param("token_type_hint", hint);
    if (clientId != null) {
      request = request.with(httpBasic(clientId, secret));
    }
    return mvc.perform(request).andReturn();
  }

  /** The complete flow for client1, returning the token response body. */
  protected JsonNode obtainTokens() throws Exception {
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT1, REDIRECT1, challengeOf(verifier));
    MvcResult response = exchange(CLIENT1, SECRET1, granted.code(), REDIRECT1, verifier);
    return JSON.readTree(response.getResponse().getContentAsString());
  }
}
