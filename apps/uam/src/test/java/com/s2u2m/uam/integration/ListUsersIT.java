package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.infrastructure.jdbc.JdbcUserRepository;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.ApiClient.Response;
import com.s2u2m.uam.support.IntegrationTestBase;
import com.s2u2m.uam.support.OpenApiContract;
import com.s2u2m.uam.support.UserFixtures;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import tools.jackson.databind.JsonNode;

/** T072：{@code GET /v1/users}——分页（默认 20、上限 100）、搜索（大小写不敏感、包含匹配、去首尾空白）、{@code accountCount}、稳定排序。 */
class ListUsersIT extends IntegrationTestBase {

  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  @Value("${local.server.port}")
  int port;

  private ApiClient api;
  private UserFixtures data;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port, TEST_SERVICE_KEY);
    data = new UserFixtures(jdbc);
  }

  private static String encoded(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  private Response list(String queryString) {
    return api.get("/v1/users" + (queryString.isEmpty() ? "" : "?" + queryString));
  }

  private Response search(String text) {
    return list("query=" + encoded(text));
  }

  private static List<String> names(Response response) {
    List<String> names = new ArrayList<>();
    response.json().get("items").forEach(item -> names.add(item.get("name").asString()));
    return names;
  }

  private void seedUsers(int count) {
    for (int i = 0; i < count; i++) {
      data.user(String.format("user-%02d", i), "ACTIVE", BASE.plusSeconds(i));
    }
  }

  private void assertValidationFailed(Response response, String field) {
    assertThat(response.status()).isEqualTo(422);
    assertThat(response.contentType()).startsWith("application/problem+json");
    JsonNode body = response.json();
    assertThat(body.get("code").asString()).isEqualTo("validation_failed");
    assertThat(body.get("errors").get(0).get("field").asString()).isEqualTo(field);
  }

  // ---- 分页与排序 ----

  @Test
  void defaultsToPageOneWithTwentyPerPageNewestFirst() {
    seedUsers(25);

    Response response = list("");

    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.json();
    assertThat(body.get("page").asInt()).isEqualTo(1);
    assertThat(body.get("size").asInt()).isEqualTo(20);
    assertThat(body.get("total").asLong()).isEqualTo(25);
    List<String> names = names(response);
    assertThat(names).hasSize(20);
    assertThat(names.get(0)).isEqualTo("user-24");
    assertThat(names.get(19)).isEqualTo("user-05");
  }

  @Test
  void secondPageHoldsTheRemainder() {
    seedUsers(25);

    Response response = list("page=2");

    assertThat(names(response))
        .containsExactly("user-04", "user-03", "user-02", "user-01", "user-00");
    assertThat(response.json().get("page").asInt()).isEqualTo(2);
    assertThat(response.json().get("total").asLong()).isEqualTo(25);
  }

  @Test
  void aPageBeyondTheEndIsEmptyButKeepsTheTotal() {
    seedUsers(25);

    Response response = list("page=9&size=10");

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().get("items")).isEmpty();
    assertThat(response.json().get("total").asLong()).isEqualTo(25);
  }

  @Test
  void pagesAreDisjointAndTogetherCoverEveryUser() {
    seedUsers(25);

    Set<String> ids = new HashSet<>();
    int seen = 0;
    for (int page = 1; page <= 4; page++) {
      Response response = list("page=" + page + "&size=7");
      for (JsonNode item : response.json().get("items")) {
        ids.add(item.get("id").asString());
        seen++;
      }
    }

    assertThat(seen).isEqualTo(25);
    assertThat(ids).hasSize(25);
  }

  @Test
  void usersCreatedAtTheSameInstantKeepAStableOrder() {
    for (int i = 0; i < 6; i++) {
      data.user("same-" + i, "ACTIVE", BASE);
    }

    List<String> first = names(list("size=100"));
    List<String> second = names(list("size=100"));

    assertThat(first).hasSize(6).isEqualTo(second);
    // 同一时刻的两页拼起来恰好是整张列表（排序键含主键，没有重叠或遗漏）
    List<String> paged = new ArrayList<>(names(list("page=1&size=4")));
    paged.addAll(names(list("page=2&size=4")));
    assertThat(paged).isEqualTo(first);
  }

  @Test
  void sizeUpToOneHundredIsAcceptedAndMoreIsRejected() {
    seedUsers(101);

    Response max = list("size=100");
    assertThat(max.status()).isEqualTo(200);
    assertThat(max.json().get("items")).hasSize(100);
    assertThat(max.json().get("size").asInt()).isEqualTo(100);
    assertThat(max.json().get("total").asLong()).isEqualTo(101);

    assertValidationFailed(list("size=101"), "size");
  }

  @Test
  void outOfRangePagingParametersAreRejected() {
    assertValidationFailed(list("page=0"), "page");
    assertValidationFailed(list("page=-3"), "page");
    assertValidationFailed(list("size=0"), "size");
    assertValidationFailed(list("size=-1"), "size");
  }

  @Test
  void nonNumericPagingParametersAreBadRequests() {
    Response response = list("page=abc");

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.contentType()).startsWith("application/problem+json");
  }

  @Test
  void anEmptyDatabaseListsNothing() {
    Response response = list("");

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().get("items")).isEmpty();
    assertThat(response.json().get("total").asLong()).isZero();
  }

  // ---- accountCount ----

  @Test
  void accountCountCoversZeroOneAndManyAccountsIncludingDisabledOnes() {
    UUID none = data.user("none", "ACTIVE", BASE);
    UUID one = data.user("one", "ACTIVE", BASE.plusSeconds(1));
    UUID many = data.user("many", "DISABLED", BASE.plusSeconds(2));
    data.account(one, "only-one");
    data.account(many, "many-a");
    data.account(many, "many-b", "DISABLED", BASE);
    data.account(many, "many-c", "DELETED", BASE);

    Response response = list("");

    Map<String, Integer> counts = new java.util.HashMap<>();
    Map<String, String> statuses = new java.util.HashMap<>();
    response
        .json()
        .get("items")
        .forEach(
            item -> {
              counts.put(item.get("name").asString(), item.get("accountCount").asInt());
              statuses.put(item.get("name").asString(), item.get("status").asString());
            });
    assertThat(counts).containsEntry("none", 0).containsEntry("one", 1).containsEntry("many", 3);
    assertThat(statuses).containsEntry("many", "DISABLED").containsEntry("none", "ACTIVE");
    assertThat(none).isNotNull();
  }

  // ---- 搜索 ----

  @Test
  void searchesTheUserNameCaseInsensitivelyByContainment() {
    data.user("Alice Wong", "ACTIVE", BASE);
    data.user("ALICIA", "ACTIVE", BASE.plusSeconds(1));
    data.user("bob", "ACTIVE", BASE.plusSeconds(2));
    data.user("malice", "ACTIVE", BASE.plusSeconds(3));

    Response response = search("aLiC");

    assertThat(names(response)).containsExactly("malice", "ALICIA", "Alice Wong");
    assertThat(response.json().get("total").asLong()).isEqualTo(3);
  }

  @Test
  void searchesTheAccountNameCaseInsensitivelyByContainment() {
    UUID zhang = data.user("张三", "ACTIVE", BASE);
    UUID li = data.user("李四", "ACTIVE", BASE.plusSeconds(1));
    data.account(zhang, "Zhang.San");
    data.account(li, "lisi");

    Response response = search("ZHANG.s");

    assertThat(names(response)).containsExactly("张三");
    assertThat(response.json().get("total").asLong()).isEqualTo(1);
  }

  @Test
  void aUserMatchedByNameAndSeveralAccountsAppearsOnceWithAllItsAccountsCounted() {
    UUID dev = data.user("dev team", "ACTIVE", BASE);
    data.account(dev, "dev1");
    data.account(dev, "dev2");
    data.account(dev, "other");

    Response response = search("dev");

    assertThat(response.json().get("total").asLong()).isEqualTo(1);
    assertThat(response.json().get("items")).hasSize(1);
    // 只匹配到其中两个账号（加用户名），但 accountCount 是该用户的全部账号
    assertThat(response.json().get("items").get(0).get("accountCount").asInt()).isEqualTo(3);
  }

  @Test
  void aUserFoundOnlyThroughOneOfItsAccountsKeepsItsFullAccountCount() {
    UUID user = data.user("王五", "ACTIVE", BASE);
    data.account(user, "wangwu");
    data.account(user, "needle-account");

    Response response = search("NEEDLE");

    assertThat(names(response)).containsExactly("王五");
    assertThat(response.json().get("items").get(0).get("accountCount").asInt()).isEqualTo(2);
  }

  @Test
  void searchCoversDisabledUsersToo() {
    data.user("gone-user", "DISABLED", BASE);

    assertThat(names(search("gone"))).containsExactly("gone-user");
  }

  @Test
  void theQueryIsTrimmedAndABlankQueryMeansNoSearch() {
    seedUsers(3);
    data.user("alice", "ACTIVE", BASE.plusSeconds(100));

    assertThat(names(search("  ALI  "))).containsExactly("alice");
    assertThat(names(search("   "))).hasSize(4);
    assertThat(names(list("query="))).hasSize(4);
    assertThat(search("   ").json().get("total").asLong()).isEqualTo(4);
  }

  @Test
  void searchTotalAndPagesFollowTheMatches() {
    for (int i = 0; i < 25; i++) {
      data.user(String.format("match-%02d", i), "ACTIVE", BASE.plusSeconds(i));
    }
    data.user("other", "ACTIVE", BASE.plusSeconds(100));

    Response page2 = list("query=" + encoded("MATCH") + "&page=2&size=10");

    assertThat(page2.json().get("total").asLong()).isEqualTo(25);
    assertThat(page2.json().get("page").asInt()).isEqualTo(2);
    assertThat(names(page2)).hasSize(10).first().isEqualTo("match-14");
    assertThat(names(list("query=" + encoded("MATCH") + "&page=3&size=10"))).hasSize(5);
  }

  @Test
  void likeWildcardsInTheQueryMatchLiterally() {
    data.user("100%", "ACTIVE", BASE);
    data.user("1000", "ACTIVE", BASE.plusSeconds(1));
    data.user("a_b", "ACTIVE", BASE.plusSeconds(2));
    data.user("axb", "ACTIVE", BASE.plusSeconds(3));
    data.user("back\\slash", "ACTIVE", BASE.plusSeconds(4));
    data.user("plain", "ACTIVE", BASE.plusSeconds(5));
    UUID underscored = data.user("holder", "ACTIVE", BASE.plusSeconds(6));
    data.account(underscored, "x_y");
    data.account(underscored, "xzy");

    assertThat(names(search("%"))).containsExactly("100%");
    assertThat(names(search("00%"))).containsExactly("100%");
    assertThat(names(search("_"))).containsExactly("holder", "a_b");
    assertThat(names(search("a_b"))).containsExactly("a_b");
    assertThat(names(search("x_y"))).containsExactly("holder");
    assertThat(names(search("\\"))).containsExactly("back\\slash");
    assertThat(names(search("k\\s"))).containsExactly("back\\slash");
    assertThat(names(search("%%"))).isEmpty();
  }

  @Test
  void aQueryLongerThanSixtyFourCharactersIsRejected() {
    assertThat(search("a".repeat(64)).status()).isEqualTo(200);

    Response response = search("a".repeat(65));

    assertValidationFailed(response, "query");
    assertThat(response.json().get("errors").get(0).get("reason").asString()).isEqualTo("too_long");
  }

  /**
   * 搜索走 {@code pg_trgm} GIN 索引。用仓储真正使用的匹配 SQL 做 {@code EXPLAIN}；数据量小时规划器会偏向顺序扫描，所以在<b>同一条连接</b>上
   * {@code SET enable_seqscan = off} 后再取计划，随后 {@code RESET}：只要谓词写法让索引<b>不可用</b>（如 {@code
   * lower(name) LIKE}），计划里就没有 这两个索引，断言即失败。
   */
  @Test
  void searchUsesTheTrigramGinIndexes() {
    seedUsers(30);
    UUID user = data.user("trigram-user", "ACTIVE", BASE);
    data.account(user, "trigram-login");
    String sql =
        "EXPLAIN " + JdbcUserRepository.MATCHED_USER_IDS_SQL.replace(":pattern", "'%trigram%'");

    String plan =
        jdbc.execute(
            (ConnectionCallback<String>)
                connection -> {
                  StringBuilder lines = new StringBuilder();
                  try (var statement = connection.createStatement()) {
                    statement.execute("SET enable_seqscan = off");
                    try (var rows = statement.executeQuery(sql)) {
                      while (rows.next()) {
                        lines.append(rows.getString(1)).append('\n');
                      }
                    } finally {
                      statement.execute("RESET enable_seqscan");
                    }
                  }
                  return lines.toString();
                });

    assertThat(plan)
        .contains("Bitmap Index Scan on idx_app_user_name_trgm")
        .contains("Bitmap Index Scan on idx_account_login_name_norm_trgm")
        .doesNotContain("Seq Scan");
  }

  // ---- 其他 ----

  @Test
  void theResponseNeverCarriesPasswordHashCredentialOrSessionFields() {
    UUID user = data.user("secretive");
    UUID account = data.account(user, "secretive-login");
    data.credential(account, "{argon2}fake-hash-for-test");

    Response response = list("");

    assertThat(OpenApiContract.fieldNames(response.json()))
        .noneMatch(
            name -> {
              String lower = name.toLowerCase(java.util.Locale.ROOT);
              return lower.contains("password")
                  || lower.contains("hash")
                  || lower.contains("credential")
                  || lower.contains("session");
            });
    assertThat(response.body()).doesNotContain("argon2").doesNotContain("fake-hash");
  }

  @Test
  void operatorHeadersAreOptionalOnReads() {
    seedUsers(2);
    Map<String, String> operator =
        Map.of(
            "X-Operator-User-Id", UUID.randomUUID().toString(),
            "X-Operator-Account-Id", UUID.randomUUID().toString(),
            "X-Operator-Session-Ref", UUID.randomUUID().toString());

    assertThat(api.get("/v1/users").status()).isEqualTo(200);
    Response withOperator = api.get("/v1/users", operator);
    assertThat(withOperator.status()).isEqualTo(200);
    assertThat(withOperator.json().get("total").asLong()).isEqualTo(2);
  }

  @Test
  void aMissingServiceKeyIsRejected() {
    Response response = new ApiClient(port, null).get("/v1/users");

    assertThat(response.status()).isEqualTo(401);
    assertThat(response.json().get("code").asString()).isEqualTo("client_unauthenticated");
  }
}
