package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeyProviderTest {

  @TempDir Path dir;

  /** Writes a fresh ES256 (P-256) private key as PKCS#8 PEM, the format the provider accepts. */
  static Path writeEcPem(Path dir, String name) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair pair = generator.generateKeyPair();
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
    Path file = dir.resolve(name);
    Files.writeString(
        file, "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n");
    return file;
  }

  @Test
  void firstFileIsTheCurrentSigningKeyAndTheRestAreVerifyOnly() throws Exception {
    Path current = writeEcPem(dir, "current.pem");
    Path previous = writeEcPem(dir, "previous.pem");

    SigningKeyProvider provider = new SigningKeyProvider(List.of(current, previous), false);

    assertThat(provider.current().isPrivate()).isTrue();
    assertThat(provider.verifyOnly()).hasSize(1);
    assertThat(provider.verifyOnly().get(0).isPrivate()).isFalse();
    assertThat(provider.all()).hasSize(2);
    assertThat(provider.current().getKeyID())
        .isNotBlank()
        .isNotEqualTo(provider.verifyOnly().get(0).getKeyID());
  }

  @Test
  void keyIdIsStableForTheSameKeyMaterial() throws Exception {
    Path file = writeEcPem(dir, "k.pem");

    SigningKeyProvider first = new SigningKeyProvider(List.of(file), false);
    SigningKeyProvider second = new SigningKeyProvider(List.of(file), false);

    assertThat(first.current().getKeyID()).isEqualTo(second.current().getKeyID());
  }

  @Test
  void failsWithoutKeysUnlessEphemeralKeysAreAllowed() {
    assertThatThrownBy(() -> new SigningKeyProvider(List.of(), false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("signing key");

    SigningKeyProvider ephemeral = new SigningKeyProvider(List.of(), true);
    assertThat(ephemeral.current().isPrivate()).isTrue();
    assertThat(ephemeral.verifyOnly()).isEmpty();
  }

  @Test
  void rejectsMalformedPemWithoutEchoingItsContent() throws IOException {
    Path bad = dir.resolve("bad.pem");
    Files.writeString(
        bad, "-----BEGIN PRIVATE KEY-----\nTOPSECRETGARBAGE\n-----END PRIVATE KEY-----\n");

    assertThatThrownBy(() -> new SigningKeyProvider(List.of(bad), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bad.pem")
        .hasMessageNotContaining("TOPSECRETGARBAGE");
  }

  @Test
  void rejectsDuplicateKeys() throws Exception {
    Path file = writeEcPem(dir, "dup.pem");

    assertThatThrownBy(() -> new SigningKeyProvider(List.of(file, file), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void toStringNeverExposesPrivateKeyMaterial() throws Exception {
    SigningKeyProvider provider = new SigningKeyProvider(List.of(writeEcPem(dir, "k.pem")), false);

    String privateParameter = provider.current().getD().toString();

    assertThat(provider.toString()).doesNotContain(privateParameter);
  }
}
