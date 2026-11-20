package tech.toolkit.tenantcore.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.support.TestJwt;
import tech.toolkit.tenantcore.support.TestKeys;

class InternalJwtVerifierTest {

  private final AdminCenterProperties.Jwt config =
      new AdminCenterProperties.Jwt(
          TestJwt.ISSUER, TestJwt.AUDIENCE, TestKeys.publicKeyPem(), Duration.ofSeconds(30));
  private final InternalJwtVerifier verifier = new InternalJwtVerifier(config, Clock.systemUTC());

  private final Long user = 42L;
  private final UUID sid = UUID.randomUUID();

  private TestJwt userJwt() {
    return TestJwt.user(user, sid, 0L);
  }

  @Test
  void acceptsAValidUserToken() {
    InternalJwtClaims c = verifier.verify(userJwt().sign());
    assertThat(c.type()).isEqualTo(InternalJwtClaims.Type.USER);
    assertThat(c.userId()).isEqualTo(user);
    assertThat(c.sessionId()).isEqualTo(sid);
    assertThat(c.tenantId()).isZero();
    assertThat(c.clientIp()).isEqualTo("203.0.113.5");
    assertThat(c.jti()).isNotBlank();
  }

  @Test
  void acceptsAServiceTokenWithTenantAndNoUserClaims() {
    InternalJwtClaims c = verifier.verify(TestJwt.service(7L).sign());
    assertThat(c.type()).isEqualTo(InternalJwtClaims.Type.SERVICE);
    assertThat(c.tenantId()).isEqualTo(7L);
    assertThat(c.userId()).isNull();
    assertThat(c.sessionId()).isNull();
  }

  @Test
  void rejectsWrongIssuerOrAudience() {
    assertThatThrownBy(() -> verifier.verify(userJwt().issuer("someone-else").sign()))
        .isInstanceOf(InvalidInternalJwtException.class);
    assertThatThrownBy(() -> verifier.verify(userJwt().audience("other-api").sign()))
        .isInstanceOf(InvalidInternalJwtException.class);
  }

  @Test
  void toleratesSmallClockSkewButRejectsExpiredTokens() {
    assertThat(verifier.verify(userJwt().expiresAt(Instant.now().minusSeconds(10)).sign()))
        .isNotNull();
    assertThatThrownBy(
            () -> verifier.verify(userJwt().expiresAt(Instant.now().minusSeconds(120)).sign()))
        .isInstanceOf(InvalidInternalJwtException.class);
  }

  @Test
  void rejectsTokensSignedWithAnotherKey() throws Exception {
    OctetKeyPair other = new OctetKeyPairGenerator(Curve.Ed25519).keyID("test-key").generate();
    assertThatThrownBy(() -> verifier.verify(userJwt().signedWith(other, "test-key").sign()))
        .isInstanceOf(InvalidInternalJwtException.class);
  }

  @Test
  void rejectsAlgNoneAndSymmetricAlgorithms() {
    assertThatThrownBy(() -> verifier.verify(userJwt().unsigned()))
        .isInstanceOf(InvalidInternalJwtException.class);
    assertThatThrownBy(() -> verifier.verify(userJwt().hs256()))
        .isInstanceOf(InvalidInternalJwtException.class);
  }

  @Test
  void rejectsMissingRequiredClaims() {
    assertThatThrownBy(() -> verifier.verify(userJwt().without("sid").sign()))
        .isInstanceOf(InvalidInternalJwtException.class);
    assertThatThrownBy(() -> verifier.verify(userJwt().without("tenant_id").sign()))
        .isInstanceOf(InvalidInternalJwtException.class);
    assertThatThrownBy(() -> verifier.verify(userJwt().without("sub").sign()))
        .isInstanceOf(InvalidInternalJwtException.class);
    assertThatThrownBy(() -> verifier.verify(userJwt().without("typ").sign()))
        .isInstanceOf(InvalidInternalJwtException.class);
  }

  @Test
  void rejectsGarbage() {
    assertThatThrownBy(() -> verifier.verify("not.a.jwt"))
        .isInstanceOf(InvalidInternalJwtException.class);
    assertThatThrownBy(() -> verifier.verify("")).isInstanceOf(InvalidInternalJwtException.class);
  }

  @Test
  void supportsKeyRotationByKid() throws Exception {
    OctetKeyPair newKey = new OctetKeyPairGenerator(Curve.Ed25519).keyID("key-2").generate();
    String jwks =
        new JWKSet(java.util.List.of(TestKeys.keyPair().toPublicJWK(), newKey.toPublicJWK()))
            .toString();
    InternalJwtVerifier rotating =
        new InternalJwtVerifier(
            new AdminCenterProperties.Jwt(
                TestJwt.ISSUER, TestJwt.AUDIENCE, jwks, Duration.ofSeconds(30)),
            Clock.systemUTC());

    assertThat(rotating.verify(userJwt().sign())).isNotNull();
    assertThat(rotating.verify(userJwt().signedWith(newKey, "key-2").sign())).isNotNull();
    assertThatThrownBy(() -> rotating.verify(userJwt().signedWith(newKey, "unknown-kid").sign()))
        .isInstanceOf(InvalidInternalJwtException.class);
  }

  @Test
  void refusesToStartWithoutAPublicKey() {
    assertThatThrownBy(
            () ->
                new InternalJwtVerifier(
                    new AdminCenterProperties.Jwt(
                        TestJwt.ISSUER, TestJwt.AUDIENCE, "", Duration.ofSeconds(30)),
                    Clock.systemUTC()))
        .isInstanceOf(IllegalStateException.class);
  }
}
