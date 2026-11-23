package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwkSourceConfigTest {

  @TempDir Path dir;

  private JWKSource<SecurityContext> sourceFor(SigningKeyProvider provider) {
    return new JwkSourceConfig().jwkSource(provider);
  }

  private List<JWK> allKeys(JWKSource<SecurityContext> source) throws Exception {
    return source.get(new JWKSelector(new JWKMatcher.Builder().build()), null);
  }

  @Test
  void containsTheCurrentAndEveryVerifyOnlyKey() throws Exception {
    SigningKeyProvider provider =
        new SigningKeyProvider(
            List.of(
                SigningKeyProviderTest.writeEcPem(dir, "current.pem"),
                SigningKeyProviderTest.writeEcPem(dir, "previous.pem")),
            false);

    List<JWK> keys = allKeys(sourceFor(provider));

    assertThat(keys)
        .extracting(JWK::getKeyID)
        .containsExactlyInAnyOrder(
            provider.current().getKeyID(), provider.verifyOnly().get(0).getKeyID());
  }

  @Test
  void onlyTheCurrentKeyCanSign() throws Exception {
    SigningKeyProvider provider =
        new SigningKeyProvider(
            List.of(
                SigningKeyProviderTest.writeEcPem(dir, "current.pem"),
                SigningKeyProviderTest.writeEcPem(dir, "previous.pem")),
            false);

    List<JWK> keys = allKeys(sourceFor(provider));

    assertThat(keys)
        .filteredOn(JWK::isPrivate)
        .extracting(JWK::getKeyID)
        .containsExactly(provider.current().getKeyID());
  }

  @Test
  void publishedKeySetHasNoPrivateParameters() throws Exception {
    SigningKeyProvider provider =
        new SigningKeyProvider(List.of(SigningKeyProviderTest.writeEcPem(dir, "k.pem")), false);

    JWKSet published = new JWKSet(allKeys(sourceFor(provider))).toPublicJWKSet();

    assertThat(published.getKeys()).allMatch(k -> !k.isPrivate());
    assertThat(published.toString()).doesNotContain("\"d\"");
    assertThat(published.getKeys()).allMatch(k -> "ES256".equals(k.getAlgorithm().getName()));
  }
}
