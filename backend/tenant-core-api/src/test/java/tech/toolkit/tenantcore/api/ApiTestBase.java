package tech.toolkit.tenantcore.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tech.toolkit.tenantcore.common.DatabaseConfig;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Boots the real API on a random port against the shared Testcontainers database. */
@SpringBootTest(
    classes = TenantCoreApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
public abstract class ApiTestBase extends PostgresTestBase {

  protected static final String NEW_PASSWORD = "a brand new passphrase";

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    DatabaseConfig config = databaseConfig();
    registry.add("tenant-core.db.jdbc-url", config::jdbcUrl);
    registry.add("tenant-core.db.owner-user", config::ownerUser);
    registry.add("tenant-core.db.owner-password", config::ownerPassword);
    registry.add("tenant-core.db.app-user", config::appUser);
    registry.add("tenant-core.db.app-password", config::appPassword);
  }

  @Autowired protected TestRestTemplate rest;
  @Autowired protected ObjectMapper mapper;

  protected record Resp(int status, JsonNode json, String raw) {}

  protected Resp call(HttpMethod method, String path, Map<String, String> headers, Object body) {
    HttpHeaders httpHeaders = new HttpHeaders();
    headers.forEach(httpHeaders::set);
    if (body != null) {
      httpHeaders.setContentType(MediaType.APPLICATION_JSON);
    }
    ResponseEntity<String> response =
        rest.exchange(path, method, new HttpEntity<>(body, httpHeaders), String.class);
    String raw = response.getBody() == null ? "" : response.getBody();
    JsonNode json = null;
    try {
      json = raw.isBlank() ? null : mapper.readTree(raw);
    } catch (Exception ignored) {
      // not JSON
    }
    return new Resp(response.getStatusCode().value(), json, raw);
  }

  protected static Map<String, String> session(String sessionId) {
    return Map.of("X-Session-Id", sessionId);
  }

  protected static Map<String, String> bearer(String jwt) {
    return Map.of("Authorization", "Bearer " + jwt);
  }

  protected Resp login(String tenant, String username, String password) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantSlug", tenant);
    body.put("username", username);
    body.put("password", password);
    return call(HttpMethod.POST, "/v1/auth/login", Map.of(), body);
  }

  /** Creates Tenant, User, Account and subscription, logs in and changes the initial password. */
  protected String readySession(String tenant, String username) {
    bootstrap(core(), tenant, username);
    String initial = login(tenant, username, PASSWORD).json().get("token").asText();
    Resp changed =
        call(
            HttpMethod.POST,
            "/v1/auth/change-password",
            session(initial),
            Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD));
    return changed.json().get("token").asText();
  }

  protected String exchange(String sessionId) {
    return call(HttpMethod.POST, "/v1/auth/exchange", session(sessionId), null)
        .json()
        .get("jwt")
        .asText();
  }
}
