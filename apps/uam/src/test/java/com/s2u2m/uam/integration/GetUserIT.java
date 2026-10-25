package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.OpenApiContract;
import com.s2u2m.uam.support.UserFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/** T073：{@code GET /v1/users/{userId}}——全部账号无遗漏、不混入他人账号；无账号为空数组；不存在 404；不含凭据。 */
class GetUserIT extends IntegrationTestBase {

  private static final Instant BASE = Instant.parse("2026-02-01T08:00:00Z");

  @Value("${local.server.port}")
  int port;

  private ApiClient api;
  private UserFixtures data;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    data = new UserFixtures(jdbc);
  }

  private Response get(UUID userId) {
    return api.get("/v1/users/" + userId);
  }

  private static List<String> loginNames(JsonNode body) {
    List<String> names = new ArrayList<>();
    body.get("accounts").forEach(account -> names.add(account.get("loginName").asString()));
    return names;
  }

  @Test
  void listsEveryAccountOfTheUserWithItsFieldsAndNoneOfAnotherUsers() {
    UUID alice = data.user("爱丽丝", "ACTIVE", BASE);
    UUID bob = data.user("鲍勃", "ACTIVE", BASE);
    UUID first = data.account(alice, "Alice", "ACTIVE", BASE.plusSeconds(10));
    UUID second = data.account(alice, "alice.work", "DISABLED", BASE.plusSeconds(20));
    UUID third = data.account(alice, "alice.old", "DELETED", BASE.plusSeconds(30));
    data.account(bob, "bob");

    Response response = get(alice);

    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.json();
    assertThat(body.get("id").asString()).isEqualTo(alice.toString());
    assertThat(body.get("name").asString()).isEqualTo("爱丽丝");
    assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(Instant.parse(body.get("createdAt").asString())).isEqualTo(BASE);
    assertThat(body.get("accounts")).hasSize(3);
    // 按创建时间升序；账号名保留创建时的写法
    assertThat(loginNames(body)).containsExactly("Alice", "alice.work", "alice.old");
    JsonNode firstAccount = body.get("accounts").get(0);
    assertThat(firstAccount.get("id").asString()).isEqualTo(first.toString());
    assertThat(firstAccount.get("kind").asString()).isEqualTo("PASSWORD");
    assertThat(firstAccount.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(Instant.parse(firstAccount.get("createdAt").asString()))
        .isEqualTo(BASE.plusSeconds(10));
    assertThat(body.get("accounts").get(1).get("id").asString()).isEqualTo(second.toString());
    assertThat(body.get("accounts").get(1).get("status").asString()).isEqualTo("DISABLED");
    assertThat(body.get("accounts").get(2).get("id").asString()).isEqualTo(third.toString());
    assertThat(body.get("accounts").get(2).get("status").asString()).isEqualTo("DELETED");
  }

  @Test
  void aUserWithoutAccountsHasAnEmptyArray() {
    UUID lonely = data.user("孤身一人", "ACTIVE", BASE);

    Response response = get(lonely);

    assertThat(response.status()).isEqualTo(200);
    JsonNode accounts = response.json().get("accounts");
    assertThat(accounts.isArray()).isTrue();
    assertThat(accounts).isEmpty();
  }

  @Test
  void aDisabledUserIsShownWithItsStatusAndAccounts() {
    UUID user = data.user("已停用", "DISABLED", BASE);
    data.account(user, "disabled.user");

    JsonNode body = get(user).json();

    assertThat(body.get("status").asString()).isEqualTo("DISABLED");
    assertThat(loginNames(body)).containsExactly("disabled.user");
  }

  @Test
  void accountsWithTheSameCreationTimeComeBackInAStableOrder() {
    UUID user = data.user("同一时刻", "ACTIVE", BASE);
    for (int i = 0; i < 5; i++) {
      data.account(user, "same-" + i, "ACTIVE", BASE);
    }

    assertThat(loginNames(get(user).json())).hasSize(5).isEqualTo(loginNames(get(user).json()));
  }

  @Test
  void anUnknownUserIs404UserNotFound() {
    Response response = get(UUID.randomUUID());

    assertThat(response.status()).isEqualTo(404);
    assertThat(response.contentType()).startsWith("application/problem+json");
    JsonNode body = response.json();
    assertThat(body.get("code").asString()).isEqualTo("user_not_found");
    assertThat(body.get("status").asInt()).isEqualTo(404);
  }

  @Test
  void aMalformedUserIdIsABadRequest() {
    Response response = api.get("/v1/users/not-a-uuid");

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.contentType()).startsWith("application/problem+json");
  }

  @Test
  void theResponseNeverCarriesPasswordHashCredentialOrSessionFields() {
    UUID user = data.user("守口如瓶", "ACTIVE", BASE);
    UUID account = data.account(user, "tight-lipped");
    data.credential(account, "{argon2}fake-hash-for-test");

    Response response = get(user);

    assertThat(OpenApiContract.fieldNames(response.json()))
        .noneMatch(
            name -> {
              String lower = name.toLowerCase(Locale.ROOT);
              return lower.contains("password")
                  || lower.contains("hash")
                  || lower.contains("credential")
                  || lower.contains("session");
            });
    assertThat(response.body()).doesNotContain("argon2").doesNotContain("fake-hash");
  }

  @Test
  void operatorHeadersAreOptionalAndAServiceKeyIsRequired() {
    UUID user = data.user("读操作", "ACTIVE", BASE);

    assertThat(get(user).status()).isEqualTo(200);
    Response anonymous = new ApiClient(port, null).get("/v1/users/" + user);
    assertThat(anonymous.status()).isEqualTo(401);
    assertThat(anonymous.json().get("code").asString()).isEqualTo("client_unauthenticated");
  }
}
