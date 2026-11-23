package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.OAuthTestBase;
import tech.toolkit.auth.support.TokenVerifier;
import tools.jackson.databind.JsonNode;

/** FR-025: discovery metadata and the public keys, enough for a downstream to verify offline. */
class DiscoveryAndJwksIT extends OAuthTestBase {

  private JsonNode metadata(String path) throws Exception {
    MvcResult result = mvc.perform(get(path)).andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    return JSON.readTree(result.getResponse().getContentAsString());
  }

  private String jwks() {
    try {
      return mvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void theOidcDiscoveryDocumentDescribesTheServer() throws Exception {
    JsonNode doc = metadata("/.well-known/openid-configuration");

    assertThat(doc.get("issuer").asString()).isEqualTo(ISSUER);
    assertThat(doc.get("authorization_endpoint").asString())
        .isEqualTo(ISSUER + "/oauth2/authorize");
    assertThat(doc.get("token_endpoint").asString()).isEqualTo(ISSUER + "/oauth2/token");
    assertThat(doc.get("jwks_uri").asString()).isEqualTo(ISSUER + "/oauth2/jwks");
    assertThat(doc.get("userinfo_endpoint").asString()).isEqualTo(ISSUER + "/userinfo");
    assertThat(texts(doc.get("response_types_supported"))).containsExactly("code");
    assertThat(texts(doc.get("code_challenge_methods_supported"))).containsExactly("S256");
    assertThat(texts(doc.get("id_token_signing_alg_values_supported"))).containsExactly("ES256");
    assertThat(texts(doc.get("grant_types_supported")))
        .containsExactlyInAnyOrder("authorization_code", "refresh_token");
  }

  @Test
  void theOAuthMetadataDocumentAgreesWithIt() throws Exception {
    JsonNode oidc = metadata("/.well-known/openid-configuration");
    JsonNode oauth = metadata("/.well-known/oauth-authorization-server");

    assertThat(oauth.get("issuer")).isEqualTo(oidc.get("issuer"));
    assertThat(oauth.get("token_endpoint")).isEqualTo(oidc.get("token_endpoint"));
    assertThat(oauth.get("jwks_uri")).isEqualTo(oidc.get("jwks_uri"));
    assertThat(texts(oauth.get("code_challenge_methods_supported"))).containsExactly("S256");
    assertThat(texts(oauth.get("grant_types_supported")))
        .containsExactlyInAnyOrder("authorization_code", "refresh_token");
  }

  @Test
  void theJwksPublishesPublicEcKeysOnly() throws Exception {
    JsonNode keys = metadata("/oauth2/jwks").get("keys");

    assertThat(keys.size()).isGreaterThanOrEqualTo(1);
    for (JsonNode key : keys) {
      assertThat(key.get("kty").asString()).isEqualTo("EC");
      assertThat(key.get("crv").asString()).isEqualTo("P-256");
      assertThat(key.get("kid").asString()).isNotBlank();
      assertThat(key.has("d")).isFalse();
    }
  }

  @Test
  void aTokenFromTheLoginFlowVerifiesOfflineWithThePublishedKeys() throws Exception {
    String token = obtainTokens().get("access_token").asString();
    TokenVerifier downstream = new TokenVerifier(ISSUER, this::jwks, Clock.systemUTC());

    var claims = downstream.verify(token);

    assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    assertThat(claims.getExpirationTime()).isAfter(new java.util.Date());
    assertThat(SignedJWT.parse(token).getHeader().getType().getType()).isEqualTo("at+jwt");
    assertThat(JWKSet.parse(jwks()).getKeyByKeyId(SignedJWT.parse(token).getHeader().getKeyID()))
        .isNotNull();
  }

  private static java.util.List<String> texts(JsonNode array) {
    java.util.List<String> values = new java.util.ArrayList<>();
    array.forEach(n -> values.add(n.asString()));
    return values;
  }
}
