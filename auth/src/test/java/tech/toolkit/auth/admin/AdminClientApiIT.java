package tech.toolkit.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.AdminApiTestBase;
import tools.jackson.databind.JsonNode;

/** FR-021 to FR-024: the administrator's client API, as in {@code contracts/admin-api.yaml}. */
class AdminClientApiIT extends AdminApiTestBase {

  private static final String CLIENT4 = "client4";
  private static final String REDIRECT4 = "http://127.0.0.1:8084/callback";

  private MvcResult createClient(String body) throws Exception {
    return call(post("/admin/v1/clients").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static String confidential(String clientId, String redirect) {
    return """
        {"clientId":"%s","type":"CONFIDENTIAL","redirectUris":["%s"],"scopes":["openid"]}"""
        .formatted(clientId, redirect);
  }

  private String createClient4() throws Exception {
    MvcResult created = createClient(confidential(CLIENT4, REDIRECT4));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    return json(created).get("clientSecret").asString();
  }

  private int statusOfExchange(String clientId, String secret, String redirect) throws Exception {
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), clientId, redirect, challengeOf(verifier));
    return exchange(clientId, secret, granted.code(), redirect, verifier).getResponse().getStatus();
  }

  // ---- creating and reading

  @Test
  void aConfidentialClientGetsItsSecretOnceAndTheDatabaseKeepsOnlyAHash() throws Exception {
    MvcResult created = createClient(confidential(CLIENT4, REDIRECT4));

    JsonNode body = json(created);
    String secret = body.get("clientSecret").asString();
    String stored =
        ownerJdbc()
            .queryForObject(
                "select client_secret from oauth2_registered_client where client_id = ?",
                String.class,
                CLIENT4);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertThat(body.get("enabled").asBoolean()).isTrue();
    assertThat(secret).hasSizeGreaterThanOrEqualTo(32);
    assertThat(stored).isNotEqualTo(secret).doesNotContain(secret);
  }

  @Test
  void aPublicClientHasNoSecretAndMustUsePkce() throws Exception {
    MvcResult created =
        createClient(
            """
            {"clientId":"spa","type":"PUBLIC","redirectUris":["http://127.0.0.1:9000/cb"],
             "scopes":["openid"]}""");

    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertThat(json(created).has("clientSecret")).isFalse();
    String settings =
        ownerJdbc()
            .queryForObject(
                "select client_settings from oauth2_registered_client where client_id = 'spa'",
                String.class);
    assertThat(settings).contains("\"settings.client.require-proof-key\":true");
  }

  @Test
  void listAndDetailNeverShowASecret() throws Exception {
    createClient4();

    JsonNode list = json(call(get("/admin/v1/clients")));
    JsonNode detail = json(call(get("/admin/v1/clients/" + CLIENT4)));

    assertThat(list.isArray()).isTrue();
    assertThat(list.size()).isGreaterThanOrEqualTo(4);
    assertThat(list.toString()).doesNotContain("clientSecret").doesNotContain("argon2");
    assertThat(detail.has("clientSecret")).isFalse();
    assertThat(detail.get("clientId").asString()).isEqualTo(CLIENT4);
    assertThat(detail.get("type").asString()).isEqualTo("CONFIDENTIAL");
    assertThat(detail.get("redirectUris").get(0).asString()).isEqualTo(REDIRECT4);
  }

  @Test
  void anUnknownClientAnswers404() throws Exception {
    assertThat(call(get("/admin/v1/clients/nobody")).getResponse().getStatus()).isEqualTo(404);
  }

  @Test
  void aDuplicateClientIdAnswers409() throws Exception {
    createClient4();

    MvcResult again = createClient(confidential(CLIENT4, REDIRECT4));

    assertThat(again.getResponse().getStatus()).isEqualTo(409);
    assertThat(again.getResponse().getContentType()).startsWith("application/problem+json");
  }

  @Test
  void invalidRegistrationsAnswer400() throws Exception {
    assertThat(createClient("{\"clientId\":\"x\"}").getResponse().getStatus()).isEqualTo(400);
    assertThat(createClient(confidential("rel", "/relative")).getResponse().getStatus())
        .isEqualTo(400);
    assertThat(
            createClient(confidential("frag", "http://127.0.0.1:1/cb#frag"))
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            createClient(
                    """
                    {"clientId":"noscope","type":"PUBLIC","redirectUris":["http://127.0.0.1:1/cb"],
                     "scopes":[]}""")
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            createClient(confidential("bad id!", "http://127.0.0.1:1/cb"))
                .getResponse()
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void anAccessTokenThatLivesAsLongAsTheRefreshTokenIsRefused() throws Exception {
    MvcResult created =
        createClient(
            """
            {"clientId":"ttl","type":"CONFIDENTIAL","redirectUris":["http://127.0.0.1:1/cb"],
             "scopes":["openid"],"accessTokenTtlSeconds":3600,"refreshTokenTtlSeconds":600}""");

    assertThat(created.getResponse().getStatus()).isEqualTo(400);
    assertThat(
            ownerJdbc()
                .queryForObject(
                    "select count(*) from oauth2_registered_client where client_id = 'ttl'",
                    Integer.class))
        .isZero();
  }

  @Test
  void validLifetimesAreKeptAndUsedForTheTokens() throws Exception {
    String secret =
        json(createClient(
                """
                    {"clientId":"ttl","type":"CONFIDENTIAL","redirectUris":["http://127.0.0.1:1/cb"],
                     "scopes":["openid"],"accessTokenTtlSeconds":120,"refreshTokenTtlSeconds":900}"""))
            .get("clientSecret")
            .asString();

    String verifier = newVerifier();
    CodeResult granted =
        obtainCode(new Browser(), "ttl", "http://127.0.0.1:1/cb", challengeOf(verifier));
    JsonNode tokens =
        json(exchange("ttl", secret, granted.code(), "http://127.0.0.1:1/cb", verifier));

    assertThat(tokens.get("expires_in").asLong()).isBetween(100L, 120L);
    assertThat(json(call(get("/admin/v1/clients/ttl"))).get("accessTokenTtlSeconds").asInt())
        .isEqualTo(120);
  }

  // ---- changing

  @Test
  void aChangeOfRedirectUrisTakesEffectWithoutARestart() throws Exception {
    String secret = createClient4();
    String moved = "http://127.0.0.1:9084/callback";

    MvcResult updated =
        call(
            patch("/admin/v1/clients/" + CLIENT4)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirectUris\":[\"" + moved + "\"]}"));

    assertThat(updated.getResponse().getStatus()).isEqualTo(200);
    assertThat(json(updated).get("redirectUris").get(0).asString()).isEqualTo(moved);
    assertThat(statusOfExchange(CLIENT4, secret, moved)).isEqualTo(200);
  }

  @Test
  void aPatchChangesOnlyWhatItNames() throws Exception {
    createClient4();

    JsonNode updated =
        json(
            call(
                patch("/admin/v1/clients/" + CLIENT4)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"backchannelLogoutUri\":\"http://127.0.0.1:8084/backchannel\","
                            + "\"postLogoutRedirectUris\":[\"http://127.0.0.1:8084/bye\"]}")));

    assertThat(updated.get("backchannelLogoutUri").asString())
        .isEqualTo("http://127.0.0.1:8084/backchannel");
    assertThat(updated.get("postLogoutRedirectUris").get(0).asString())
        .isEqualTo("http://127.0.0.1:8084/bye");
    assertThat(updated.get("redirectUris").get(0).asString()).isEqualTo(REDIRECT4);
    assertThat(updated.get("scopes").get(0).asString()).isEqualTo("openid");
  }

  @Test
  void anUpdateThatBreaksTheLifetimeRuleAnswers400AndChangesNothing() throws Exception {
    createClient(
        """
        {"clientId":"ttl","type":"CONFIDENTIAL","redirectUris":["http://127.0.0.1:1/cb"],
         "scopes":["openid"],"accessTokenTtlSeconds":120,"refreshTokenTtlSeconds":900}""");

    MvcResult updated =
        call(
            patch("/admin/v1/clients/ttl")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessTokenTtlSeconds\":1000}"));

    assertThat(updated.getResponse().getStatus()).isEqualTo(400);
    assertThat(json(call(get("/admin/v1/clients/ttl"))).get("accessTokenTtlSeconds").asInt())
        .isEqualTo(120);
  }

  @Test
  void anUpdateOfAnUnknownClientAnswers404() throws Exception {
    MvcResult updated =
        call(
            patch("/admin/v1/clients/nobody")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopes\":[\"openid\"]}"));

    assertThat(updated.getResponse().getStatus()).isEqualTo(404);
  }

  // ---- disabling, enabling, rotating

  @Test
  void aDisabledClientIsRefusedAndAnEnabledOneWorksAgain() throws Exception {
    String secret = createClient4();
    assertThat(statusOfExchange(CLIENT4, secret, REDIRECT4)).isEqualTo(200);

    assertThat(call(post("/admin/v1/clients/" + CLIENT4 + "/disable")).getResponse().getStatus())
        .isEqualTo(204);

    assertThatThrownBy(
            () -> obtainCode(new Browser(), CLIENT4, REDIRECT4, challengeOf(newVerifier())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(json(call(get("/admin/v1/clients/" + CLIENT4))).get("enabled").asBoolean())
        .isFalse();

    assertThat(call(post("/admin/v1/clients/" + CLIENT4 + "/enable")).getResponse().getStatus())
        .isEqualTo(204);
    assertThat(statusOfExchange(CLIENT4, secret, REDIRECT4)).isEqualTo(200);
  }

  @Test
  void aDisabledClientCannotRedeemACodeItAlreadyHolds() throws Exception {
    String secret = createClient4();
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT4, REDIRECT4, challengeOf(verifier));

    call(post("/admin/v1/clients/" + CLIENT4 + "/disable"));

    assertThat(
            exchange(CLIENT4, secret, granted.code(), REDIRECT4, verifier)
                .getResponse()
                .getStatus())
        .isEqualTo(401);
  }

  @Test
  void disablingTheOneClientEndsItsRefreshTokensAndNothingElse() throws Exception {
    String secret = createClient4();
    String verifier = newVerifier();
    CodeResult granted = obtainCode(new Browser(), CLIENT4, REDIRECT4, challengeOf(verifier));
    String refresh =
        json(exchange(CLIENT4, secret, granted.code(), REDIRECT4, verifier))
            .get("refresh_token")
            .asString();
    JsonNode other = obtainTokens();

    call(post("/admin/v1/clients/" + CLIENT4 + "/disable"));

    assertThat(refresh(CLIENT4, secret, refresh).getResponse().getStatus()).isEqualTo(401);
    assertThat(
            refresh(CLIENT1, SECRET1, other.get("refresh_token").asString())
                .getResponse()
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void rotatingTheSecretEndsTheOldOneAtOnce() throws Exception {
    String oldSecret = createClient4();

    MvcResult rotated = call(post("/admin/v1/clients/" + CLIENT4 + "/secret-rotation"));
    String newSecret = json(rotated).get("clientSecret").asString();

    assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
    assertThat(newSecret).isNotEqualTo(oldSecret);
    assertThat(statusOfExchange(CLIENT4, oldSecret, REDIRECT4)).isEqualTo(401);
    assertThat(statusOfExchange(CLIENT4, newSecret, REDIRECT4)).isEqualTo(200);
  }

  @Test
  void aPublicClientHasNoSecretToRotate() throws Exception {
    createClient(
        """
        {"clientId":"spa","type":"PUBLIC","redirectUris":["http://127.0.0.1:9000/cb"],
         "scopes":["openid"]}""");

    assertThat(call(post("/admin/v1/clients/spa/secret-rotation")).getResponse().getStatus())
        .isEqualTo(400);
  }

  @Test
  void disableEnableAndRotateOfAnUnknownClientAnswer404() throws Exception {
    for (String action : new String[] {"disable", "enable", "secret-rotation"}) {
      assertThat(call(post("/admin/v1/clients/nobody/" + action)).getResponse().getStatus())
          .as(action)
          .isEqualTo(404);
    }
  }

  // ---- who may call, and what is recorded

  @Test
  void anOrdinaryUserCannotUseTheClientApi() throws Exception {
    String ordinary = obtainTokens().get("access_token").asString();

    assertThat(
            mvc.perform(withToken(get("/admin/v1/clients"), ordinary))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(mvc.perform(get("/admin/v1/clients")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
  }

  @Test
  void everyChangeIsAuditedWithItsAdministratorAndNoSecret() throws Exception {
    createClient4();
    call(
        patch("/admin/v1/clients/" + CLIENT4)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scopes\":[\"openid\"]}"));
    call(post("/admin/v1/clients/" + CLIENT4 + "/disable"));
    call(post("/admin/v1/clients/" + CLIENT4 + "/enable"));
    call(post("/admin/v1/clients/" + CLIENT4 + "/secret-rotation"));

    var events =
        ownerJdbc()
            .queryForList(
                "select type, actor, client_id, detail::text as detail from auth_audit_event"
                    + " where client_id = ? order by id",
                CLIENT4);

    assertThat(events).extracting(e -> e.get("type")).contains("CLIENT_CREATED", "CLIENT_UPDATED");
    assertThat(events).hasSize(5);
    assertThat(events)
        .allSatisfy(e -> assertThat(e.get("actor")).isEqualTo(String.valueOf(idOf(ADMIN))));
    assertThat(events.toString()).doesNotContainIgnoringCase("secret\":");
  }
}
