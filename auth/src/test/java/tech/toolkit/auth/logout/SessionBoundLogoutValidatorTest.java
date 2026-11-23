package tech.toolkit.auth.logout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;

/**
 * The extra logout check that does not depend on the framework's in-memory session registry: the ID
 * token hint must belong to the login the request comes from.
 */
class SessionBoundLogoutValidatorTest {

  private static final String SESSION_ID = "9c2d7f1e-6a43-4f3e-8f0b-5d1e2a3b4c5d";

  /** The framework's algorithm: SHA-256 of the session id, base64url without padding. */
  private static String hashOf(String sessionId) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  private static OidcLogoutAuthenticationToken request(String sessionId, String sidClaim) {
    OidcIdToken.Builder idToken =
        OidcIdToken.withTokenValue("token")
            .issuer("http://localhost:9000")
            .subject("1")
            .audience(List.of("client1"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    if (sidClaim != null) {
      idToken.claim("sid", sidClaim);
    }
    return new OidcLogoutAuthenticationToken(
        idToken.build(),
        UsernamePasswordAuthenticationToken.authenticated("1", "x", List.of()),
        sessionId,
        "client1",
        null,
        null);
  }

  private final SessionBoundLogoutValidator validator = new SessionBoundLogoutValidator();

  @Test
  void aHintFromTheSameLoginPasses() throws Exception {
    assertThatCode(() -> validator.verify(request(SESSION_ID, hashOf(SESSION_ID))))
        .doesNotThrowAnyException();
  }

  @Test
  void aHintFromAnotherLoginIsRejected() throws Exception {
    String otherLogin = hashOf("another-session-id");

    assertThatThrownBy(() -> validator.verify(request(SESSION_ID, otherLogin)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
        .isEqualTo("invalid_request");
  }

  @Test
  void aHintWithoutASidIsRejectedWhenThereIsALogin() {
    assertThatThrownBy(() -> validator.verify(request(SESSION_ID, null)))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }

  @Test
  void withoutALoginThereIsNothingToBindTo() throws Exception {
    assertThatCode(() -> validator.verify(request(null, hashOf(SESSION_ID))))
        .doesNotThrowAnyException();
  }

  @Test
  void theHashHelperUsesTheFrameworksAlgorithm() throws Exception {
    assertThat(tech.toolkit.auth.session.SsoSessionIds.hash(SESSION_ID))
        .isEqualTo(hashOf(SESSION_ID))
        .hasSize(43);
  }
}
