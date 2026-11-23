package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tech.toolkit.auth.support.TestTokens;
import tech.toolkit.auth.support.TokenVerifier;
import tech.toolkit.auth.support.TokenVerifier.InvalidTokenException;

/** FR-025: what a downstream service must refuse, checked with its own verification tool. */
class TokenVerificationTest {

  private static final String ISSUER = "http://localhost:9000";

  private final ECKey key = TestTokens.newKey();

  private TokenVerifier verifierFor(ECKey... published) {
    String jwks = new JWKSet(java.util.List.of(published)).toPublicJWKSet().toString();
    return new TokenVerifier(ISSUER, () -> jwks, Clock.systemUTC());
  }

  @Test
  void aWellFormedTokenIsAccepted() {
    var claims = verifierFor(key).verify(TestTokens.accessToken(key, ISSUER));

    assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    assertThat(claims.getSubject()).isEqualTo("1");
  }

  @Test
  void aTamperedPayloadIsRefused() {
    String token = TestTokens.accessToken(key, ISSUER);
    String[] parts = token.split("\\.");
    String forged =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                    .replace("\"sub\":\"1\"", "\"sub\":\"2\"")
                    .getBytes());

    assertThat(forged).isNotEqualTo(parts[1]);
    assertThatThrownBy(() -> verifierFor(key).verify(parts[0] + "." + forged + "." + parts[2]))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void anExpiredTokenIsRefused() {
    String token = TestTokens.sign(key, "at+jwt", ISSUER, Instant.now().minusSeconds(5));

    assertThatThrownBy(() -> verifierFor(key).verify(token))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void aTokenFromAnotherIssuerIsRefused() {
    String token = TestTokens.accessToken(key, "http://evil.example");

    assertThatThrownBy(() -> verifierFor(key).verify(token))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("issuer");
  }

  @Test
  void anIdTokenCannotStandInForAnAccessToken() {
    String token = TestTokens.sign(key, "JWT", ISSUER, Instant.now().plusSeconds(600));

    assertThatThrownBy(() -> verifierFor(key).verify(token))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("at+jwt");
  }

  @Test
  void aTokenSignedWithAKeyThatIsNotPublishedIsRefused() {
    ECKey stranger = TestTokens.newKey();

    assertThatThrownBy(() -> verifierFor(key).verify(TestTokens.accessToken(stranger, ISSUER)))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("unknown key");
  }

  @Test
  void aStrangerCannotPassOffItsKeyUnderTheKidOfAPublishedOne() throws Exception {
    ECKey stranger =
        new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256)
            .keyID(key.getKeyID())
            .generate();

    assertThatThrownBy(() -> verifierFor(key).verify(TestTokens.accessToken(stranger, ISSUER)))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void anUnsignedOrGarbledTokenIsRefused() {
    assertThatThrownBy(() -> verifierFor(key).verify("not.a.token"))
        .isInstanceOf(InvalidTokenException.class);
    String unsigned =
        java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes())
            + "."
            + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes())
            + ".";
    assertThatThrownBy(() -> verifierFor(key).verify(unsigned))
        .isInstanceOf(InvalidTokenException.class);
  }
}
