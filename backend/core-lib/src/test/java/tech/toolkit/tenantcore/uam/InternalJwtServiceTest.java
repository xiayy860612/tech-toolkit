package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.toolkit.tenantcore.common.DomainException;
import tech.toolkit.tenantcore.common.ErrorCode;
import tech.toolkit.tenantcore.support.PostgresTestBase.MutableClock;
import tech.toolkit.tenantcore.uam.InternalJwtService.IssuedJwt;
import tech.toolkit.tenantcore.uam.InternalJwtService.JwtPrincipal;

class InternalJwtServiceTest {

  private final MutableClock clock = new MutableClock();
  private final UamSettings settings = UamSettings.ephemeral();
  private final SigningKeyProvider keys = new SigningKeyProvider(settings);
  private final InternalJwtService jwt = new InternalJwtService(keys, settings, clock);
  private final UUID tenantId = UUID.randomUUID();

  private static void assertUnauthorized(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHORIZED));
  }

  @Test
  void issuesTheClaimsOfTheInternalJwtContract() throws Exception {
    IssuedJwt issued = jwt.issue("tu_abc123def456", tenantId, "s_0123456789abcdef");
    JWTClaimsSet claims = SignedJWT.parse(issued.jwt()).getJWTClaimsSet();

    assertThat(claims.getIssuer()).isEqualTo(settings.issuer());
    assertThat(claims.getAudience()).containsExactly(settings.audience());
    assertThat(claims.getSubject()).isEqualTo("tu_abc123def456");
    assertThat(claims.getJWTID()).isNotBlank();
    assertThat(claims.getStringClaim("tenant_id")).isEqualTo(tenantId.toString());
    assertThat(claims.getStringClaim("cell_id")).isEqualTo(settings.cellId());
    assertThat(claims.getStringClaim("client_type")).isEqualTo("web");
    assertThat(claims.getStringClaim("azp")).isEqualTo("platform-web");
    assertThat(claims.getStringClaim("sid")).isEqualTo("s_0123456789abcdef");
    assertThat(claims.getClaim("scope")).isNull();
    assertThat(
            Duration.between(
                claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
        .isEqualTo(Duration.ofSeconds(120));
    assertThat(issued.expiresAt()).isEqualTo(claims.getExpirationTime().toInstant());
  }

  @Test
  void signsWithEs256AndTheKeyIdOfThePublishedKey() throws Exception {
    SignedJWT parsed = SignedJWT.parse(jwt.issue("tu_a", tenantId, "s_a").jwt());
    assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
    assertThat(parsed.getHeader().getKeyID()).isEqualTo(keys.keyId());
  }

  @Test
  void verifiesAFreshToken() {
    JwtPrincipal principal = jwt.verify(jwt.issue("tu_abc", tenantId, "s_xyz").jwt());
    assertThat(principal.tenantUserId()).isEqualTo("tu_abc");
    assertThat(principal.tenantId()).isEqualTo(tenantId);
    assertThat(principal.sid()).isEqualTo("s_xyz");
  }

  @Test
  void rejectsExpiredTokens() {
    String token = jwt.issue("tu_a", tenantId, "s_a").jwt();
    clock.advance(Duration.ofSeconds(119));
    assertThat(jwt.verify(token).tenantUserId()).isEqualTo("tu_a");
    clock.advance(Duration.ofSeconds(2));
    assertUnauthorized(() -> jwt.verify(token));
  }

  @Test
  void rejectsATamperedSignatureOrPayload() {
    String token = jwt.issue("tu_a", tenantId, "s_a").jwt();
    String[] parts = token.split("\\.");
    String badSignature = parts[0] + "." + parts[1] + "." + flip(parts[2]);
    assertUnauthorized(() -> jwt.verify(badSignature));

    String forgedPayload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]))
                    .replace("tu_a", "tu_b")
                    .getBytes());
    assertUnauthorized(() -> jwt.verify(parts[0] + "." + forgedPayload + "." + parts[2]));
  }

  private static String flip(String signature) {
    char last = signature.charAt(signature.length() - 1);
    return signature.substring(0, signature.length() - 1) + (last == 'A' ? 'B' : 'A');
  }

  @Test
  void rejectsAnotherIssuerOrAudience() {
    String token = jwt.issue("tu_a", tenantId, "s_a").jwt();
    InternalJwtService otherIssuer =
        new InternalJwtService(keys, settings.withIssuer("https://evil.example"), clock);
    InternalJwtService otherAudience =
        new InternalJwtService(keys, settings.withAudience("tenant-services"), clock);
    assertUnauthorized(() -> otherIssuer.verify(token));
    assertUnauthorized(() -> otherAudience.verify(token));
  }

  @Test
  void rejectsTokensSignedByAnotherKey() {
    SigningKeyProvider otherKeys = new SigningKeyProvider(UamSettings.ephemeral());
    String foreign =
        new InternalJwtService(otherKeys, settings, clock).issue("tu_a", tenantId, "s_a").jwt();
    assertUnauthorized(() -> jwt.verify(foreign));
  }

  @Test
  void rejectsAlgNoneAndHmacTokens() throws JOSEException {
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"iss\":\"" + settings.issuer() + "\",\"sub\":\"tu_a\"}").getBytes());
    String none =
        Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
            + "."
            + payload
            + ".";
    assertUnauthorized(() -> jwt.verify(none));

    SignedJWT hs256 =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keys.keyId()).build(),
            new JWTClaimsSet.Builder()
                .issuer(settings.issuer())
                .audience(settings.audience())
                .subject("tu_a")
                .expirationTime(new Date(clock.instant().plusSeconds(60).toEpochMilli()))
                .claim("tenant_id", tenantId.toString())
                .claim("sid", "s_a")
                .build());
    hs256.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
    assertUnauthorized(() -> jwt.verify(hs256.serialize()));
  }

  @Test
  void rejectsGarbageAndNull() {
    assertUnauthorized(() -> jwt.verify("not-a-jwt"));
    assertUnauthorized(() -> jwt.verify(""));
    assertUnauthorized(() -> jwt.verify(null));
  }

  @Test
  void jwksPublishesOnlyThePublicKeyWithItsKeyId() {
    Map<String, Object> jwks = new JwksPublisher(keys).jwks();
    @SuppressWarnings("unchecked")
    java.util.List<Map<String, Object>> list =
        (java.util.List<Map<String, Object>>) jwks.get("keys");
    assertThat(list).hasSize(1);
    assertThat(list.get(0))
        .containsEntry("kid", keys.keyId())
        .containsEntry("kty", "EC")
        .containsEntry("crv", "P-256");
    assertThat(list.get(0)).doesNotContainKey("d");
  }

  @Test
  void aConfiguredPemKeyIsLoadedAndGivesAStableKeyId() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
    KeyPair pair = generator.generateKeyPair();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    UamSettings configured = settings.withSigningKeyPem(pem, false);
    SigningKeyProvider first = new SigningKeyProvider(configured);
    SigningKeyProvider second = new SigningKeyProvider(configured);
    assertThat(first.keyId()).isEqualTo(second.keyId()).isNotEqualTo(keys.keyId());

    InternalJwtService service = new InternalJwtService(first, configured, clock);
    assertThat(
            new InternalJwtService(second, configured, clock)
                .verify(service.issue("tu_a", tenantId, "s_a").jwt())
                .tenantUserId())
        .isEqualTo("tu_a");
  }

  @Test
  void withoutAKeyOutsideDevStartupFails() {
    UamSettings production = settings.withSigningKeyPem("", false);
    assertThatThrownBy(() -> new SigningKeyProvider(production))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UAM_SIGNING_KEY_PEM");
    UamSettings garbage = settings.withSigningKeyPem("not a pem", false);
    assertThatThrownBy(() -> new SigningKeyProvider(garbage))
        .isInstanceOf(IllegalStateException.class);
  }
}
