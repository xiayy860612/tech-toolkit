package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.OAuthTestBase;
import tools.jackson.databind.JsonNode;

class TokenClaimsIT extends OAuthTestBase {

  private static final Set<String> PERSONAL_DATA =
      Set.of(
          "email",
          "phone_number",
          "name",
          "given_name",
          "family_name",
          "password",
          "roles",
          "authorities");

  @Test
  void theAccessTokenIsAnRfc9068JwtWithoutPersonalData() throws Exception {
    JsonNode tokens = obtainTokens();

    SignedJWT jwt = SignedJWT.parse(tokens.get("access_token").asString());
    JWTClaimsSet claims = jwt.getJWTClaimsSet();

    assertThat(jwt.getHeader().getType().getType()).isEqualTo("at+jwt");
    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
    assertThat(jwt.getHeader().getKeyID()).isNotBlank();
    assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    assertThat(claims.getSubject()).matches("\\d+");
    assertThat(claims.getAudience()).containsExactly(ISSUER);
    assertThat(claims.getStringClaim("client_id")).isEqualTo(CLIENT1);
    assertThat(claims.getStringClaim("scope")).contains("openid");
    assertThat(claims.getStringClaim("sid")).isNotBlank();
    assertThat(claims.getJWTID()).isNotBlank();
    assertThat(claims.getIssueTime()).isNotNull();
    assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime())
        .isEqualTo(600_000L);
    assertThat(claims.getClaims().keySet()).doesNotContainAnyElementsOf(PERSONAL_DATA);
  }

  @Test
  void theIdTokenCarriesTheOidcClaimsAndNoPersonalData() throws Exception {
    JsonNode tokens = obtainTokens();

    JWTClaimsSet claims = SignedJWT.parse(tokens.get("id_token").asString()).getJWTClaimsSet();

    assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    assertThat(claims.getAudience()).containsExactly(CLIENT1);
    assertThat(claims.getClaim("auth_time")).isNotNull();
    assertThat(claims.getStringClaim("nonce")).isEqualTo("nonce-1");
    assertThat(claims.getStringClaim("sid")).isNotBlank();
    assertThat(claims.getStringClaim("azp")).isEqualTo(CLIENT1);
    assertThat(claims.getClaims().keySet()).doesNotContainAnyElementsOf(PERSONAL_DATA);
  }

  @Test
  void userInfoReturnsOnlySubAndPreferredUsername() throws Exception {
    JsonNode tokens = obtainTokens();
    String subject =
        SignedJWT.parse(tokens.get("access_token").asString()).getJWTClaimsSet().getSubject();

    MvcResult response =
        mvc.perform(
                get("/userinfo")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + tokens.get("access_token").asString()))
            .andReturn();

    assertThat(response.getResponse().getStatus())
        .as("WWW-Authenticate: %s", response.getResponse().getHeader("WWW-Authenticate"))
        .isEqualTo(200);
    JsonNode body = JSON.readTree(response.getResponse().getContentAsString());
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("sub", "preferred_username");
    assertThat(body.get("sub").asString()).isEqualTo(subject);
    assertThat(body.get("preferred_username").asString()).isEqualTo(USERNAME);
  }

  @Test
  void theSidIsTheFrameworksHashOfTheSessionIdAndSharedByBothTokens() throws Exception {
    JsonNode tokens = obtainTokens();

    String accessSid =
        SignedJWT.parse(tokens.get("access_token").asString())
            .getJWTClaimsSet()
            .getStringClaim("sid");
    String idSid =
        SignedJWT.parse(tokens.get("id_token").asString()).getJWTClaimsSet().getStringClaim("sid");
    String sessionId =
        ownerJdbc().queryForObject("select session_id from spring_session", String.class);
    // SHA-256 of the HTTP session id, base64url without padding: what the framework itself writes
    byte[] digest =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(sessionId.trim().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    String expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

    assertThat(accessSid).isEqualTo(expected);
    assertThat(idSid).isEqualTo(expected);
  }

  @Test
  void theSubjectIsTheUserIdNotTheUsername() throws Exception {
    JsonNode tokens = obtainTokens();

    String subject =
        SignedJWT.parse(tokens.get("access_token").asString()).getJWTClaimsSet().getSubject();
    Long id =
        ownerJdbc()
            .queryForObject("select id from auth_user where username = ?", Long.class, USERNAME);

    assertThat(subject).isEqualTo(String.valueOf(id));
  }
}
