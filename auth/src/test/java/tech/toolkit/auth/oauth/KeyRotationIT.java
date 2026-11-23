package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import tech.toolkit.auth.support.TokenVerifier;
import tech.toolkit.auth.support.TokenVerifier.InvalidTokenException;

/**
 * FR-026, SC-007: the rotation procedure of {@code AGENTS.md}, step by step. Each stage builds the
 * signing keys, the JWKS and the token encoder from the same classes the application uses, signs
 * tokens, and checks them the way a downstream service would, with a JWKS cache that survives the
 * whole rotation.
 */
class KeyRotationIT {

  private static final String ISSUER = "http://localhost:9000";

  @TempDir Path dir;

  /** The auth instance as it runs with one list of key files (first = signs). */
  private final class Instance {
    final SigningKeyProvider keys;
    final JWKSource<SecurityContext> source;
    final NimbusJwtEncoder encoder;

    Instance(Path... files) {
      keys = new SigningKeyProvider(List.of(files), false);
      source = new JwkSourceConfig().jwkSource(keys);
      encoder = (NimbusJwtEncoder) new JwkSourceConfig().jwtEncoder(keys);
    }

    String token() {
      JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).type("at+jwt").build();
      JwtClaimsSet claims =
          JwtClaimsSet.builder()
              .issuer(ISSUER)
              .subject("1")
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(600))
              .build();
      return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** What GET /oauth2/jwks answers: the public keys only. */
    String jwks() {
      return new JWKSet(new ArrayList<>(keys.all())).toPublicJWKSet().toString();
    }
  }

  private Path newKeyFile(String name) throws Exception {
    return SigningKeyProviderTest.writeEcPem(dir, name);
  }

  private static String kidOf(String token) throws Exception {
    return SignedJWT.parse(token).getHeader().getKeyID();
  }

  @Test
  void anAccessTokenSurvivesTheWholeRotationAndNothingFailsOnTheWay() throws Exception {
    Path a = newKeyFile("a.pem");
    Path b = newKeyFile("b.pem");
    // the downstream's view of the JWKS follows whatever the instance serves at the moment
    Instance[] live = new Instance[1];
    TokenVerifier downstream = new TokenVerifier(ISSUER, () -> live[0].jwks(), Clock.systemUTC());
    List<String> failures = new ArrayList<>();
    int checked = 0;

    // 0. steady state: A signs
    live[0] = new Instance(a);
    String beforeRotation = live[0].token();
    String keyA = kidOf(beforeRotation);

    // 1. publish B behind A (verify only): A still signs, B is in the JWKS
    live[0] = new Instance(a, b);
    String duringPublish = live[0].token();
    assertThat(kidOf(duringPublish)).isEqualTo(keyA);
    assertThat(live[0].keys.verifyOnly()).hasSize(1);

    // 2. switch signing to B, A stays published
    live[0] = new Instance(b, a);
    String afterSwitch = live[0].token();
    String keyB = kidOf(afterSwitch);
    assertThat(keyB).isNotEqualTo(keyA);

    // every token from every stage must verify at every stage (SC-007)
    for (String stageName : List.of("publish", "switch")) {
      for (String token : List.of(beforeRotation, duringPublish, afterSwitch)) {
        checked++;
        try {
          downstream.verify(token);
        } catch (InvalidTokenException e) {
          failures.add(stageName + ": " + e.getMessage());
        }
      }
    }
    assertThat(failures).isEmpty();
    assertThat(checked).isEqualTo(6);

    // 3. after at least one access token lifetime, A is dropped
    live[0] = new Instance(b);
    assertThat(live[0].jwks()).contains(keyB).doesNotContain(keyA);
    assertThat(downstream.verify(live[0].token()).getSubject()).isEqualTo("1");
  }

  @Test
  void aCacheThatHasNotSeenTheNewKeyFetchesItOnTheFirstUnknownKid() throws Exception {
    Path a = newKeyFile("a.pem");
    Path b = newKeyFile("b.pem");
    Instance[] live = {new Instance(a)};
    TokenVerifier downstream = new TokenVerifier(ISSUER, () -> live[0].jwks(), Clock.systemUTC());
    downstream.verify(live[0].token());
    assertThat(downstream.fetches()).isEqualTo(1);

    live[0] = new Instance(b, a);
    String signedWithB = live[0].token();

    assertThat(downstream.verify(signedWithB).getSubject()).isEqualTo("1");
    assertThat(downstream.fetches()).isEqualTo(2);
    // known kids do not cause another fetch
    downstream.verify(signedWithB);
    assertThat(downstream.fetches()).isEqualTo(2);
  }

  @Test
  void onceTheOldKeyIsRemovedItsTokensNoLongerVerify() throws Exception {
    Path a = newKeyFile("a.pem");
    Path b = newKeyFile("b.pem");
    String oldToken = new Instance(a).token();
    Instance after = new Instance(b);
    TokenVerifier fresh = new TokenVerifier(ISSUER, after::jwks, Clock.systemUTC());

    assertThatThrownBy(() -> fresh.verify(oldToken))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("unknown key");
  }

  @Test
  void theJwksNeverCarriesAPrivateKey() throws Exception {
    Instance instance = new Instance(newKeyFile("a.pem"), newKeyFile("b.pem"));

    JWKSet published = JWKSet.parse(instance.jwks());

    assertThat(published.getKeys()).hasSize(2).noneMatch(k -> k.isPrivate());
    assertThat(published.getKeys())
        .allSatisfy(k -> assertThat(k.getAlgorithm()).isIn((Object) null, JWSAlgorithm.ES256));
    assertThat(instance.jwks()).doesNotContain("\"d\"");
    assertThat(JOSEObjectType.JWT).isNotNull();
  }
}
