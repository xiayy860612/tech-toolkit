package tech.toolkit.tenantcore.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class ListApiContractIT extends ApiTestBase {

  private JsonNode get(String path, String sessionId) {
    Resp resp = call(HttpMethod.GET, path, session(sessionId), null);
    assertThat(resp.status()).as(path).isEqualTo(200);
    return resp.json();
  }

  @Test
  void listsRequireAuthentication() {
    for (String path : new String[] {"/v1/tenants", "/v1/module-subscriptions", "/v1/users"}) {
      assertThat(call(HttpMethod.GET, path, Map.of(), null).status()).as(path).isEqualTo(401);
    }
  }

  @Test
  void tenantsHaveTheContractShapeAndNoInternalId() {
    String sessionId = readySession("acme", "admin1");
    JsonNode page = get("/v1/tenants", sessionId);
    assertThat(page.get("page").asInt()).isZero();
    assertThat(page.get("size").asInt()).isEqualTo(20);
    assertThat(page.get("total").asLong()).isEqualTo(1);
    JsonNode tenant = page.get("items").get(0);
    assertThat(tenant.get("slug").asText()).isEqualTo("acme");
    assertThat(tenant.get("name").asText()).isEqualTo("Name acme");
    assertThat(tenant.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(tenant.get("createdAt").asText()).isNotBlank();
    assertThat(tenant.has("id")).isFalse();
  }

  @Test
  void moduleSubscriptionsHaveTheContractShape() {
    String sessionId = readySession("acme", "admin1");
    JsonNode item = get("/v1/module-subscriptions", sessionId).get("items").get(0);
    assertThat(item.get("moduleCode").asText()).isEqualTo("platform");
    assertThat(item.get("tenantSlug").asText()).isEqualTo("acme");
    assertThat(item.get("status").asText()).isEqualTo("SUBSCRIBED");
    assertThat(item.get("subscribedAt").asText()).isNotBlank();
  }

  @Test
  void usersShowAccountUsernamesIncludeUsersWithoutAccountsAndNeverExposeCredentials() {
    String sessionId = readySession("acme", "admin1");
    core().users().create("acme", "Li Si", "test"); // no Account yet
    Resp resp = call(HttpMethod.GET, "/v1/users", session(sessionId), null);
    assertThat(resp.status()).isEqualTo(200);
    JsonNode items = resp.json().get("items");
    assertThat(items).hasSize(2);
    for (JsonNode user : items) {
      assertThat(user.has("tenantUserId")).isTrue();
      assertThat(user.has("displayName")).isTrue();
      assertThat(user.get("accountUsernames").isArray()).isTrue();
      assertThat(user.get("tenantSlug").asText()).isEqualTo("acme");
      assertThat(user.get("status").asText()).isEqualTo("ACTIVE");
      assertThat(user.has("id")).isFalse();
    }
    assertThat(resp.raw())
        .doesNotContain("argon2")
        .doesNotContain("password")
        .doesNotContain("hash");
    assertThat(items)
        .anySatisfy(
            u -> {
              assertThat(u.get("displayName").asText()).isEqualTo("Li Si");
              assertThat(u.get("accountUsernames")).isEmpty();
            });
  }

  @Test
  void sizeIsClampedAndPagesAreZeroBased() {
    String sessionId = readySession("acme", "admin1");
    for (int i = 0; i < 25; i++) {
      core().users().create("acme", "Person " + i, "test");
    }
    assertThat(get("/v1/users?size=1000", sessionId).get("size").asInt()).isEqualTo(100);
    JsonNode second = get("/v1/users?page=1&size=20", sessionId);
    assertThat(second.get("page").asInt()).isEqualTo(1);
    assertThat(second.get("items")).hasSize(6); // 26 users in total
    assertThat(second.get("total").asLong()).isEqualTo(26);
  }

  @Test
  void aJwtWorksToo() {
    String sessionId = readySession("acme", "admin1");
    String jwt = exchange(sessionId);
    Resp resp = call(HttpMethod.GET, "/v1/tenants", bearer(jwt), null);
    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.json().get("items").get(0).get("slug").asText()).isEqualTo("acme");
  }
}
