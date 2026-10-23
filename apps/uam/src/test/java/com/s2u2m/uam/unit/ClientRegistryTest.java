package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.domain.model.ClientIdentity;
import com.s2u2m.uam.infrastructure.security.ClientRegistry;
import com.s2u2m.uam.support.TestSecrets;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** T017：解析 {@code UAM_CLIENTS}，服务密钥按 SHA-256 哈希常量时间比较。 */
class ClientRegistryTest {

  // 明显是假的测试密钥
  private static final String KEY_A = "uamk_test_fake_key_a";
  private static final String KEY_B = "uamk_test_fake_key_b";
  private static final String KEY_OTHER = "uamk_test_fake_key_other";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static String hash(String key) {
    return "sha256:" + TestSecrets.sha256Hex(key);
  }

  private static ClientRegistry registry(String json) {
    return ClientRegistry.fromJson(MAPPER, json);
  }

  @Test
  void parsesClientsAndAuthenticatesByKey() {
    ClientRegistry registry =
        registry(
            """
            [{"id":"admin-center","keyHashes":["%s"],"permissions":["assert-end-user-context"]}]
            """
                .formatted(hash(KEY_A)));

    Optional<ClientIdentity> identity = registry.authenticate(KEY_A);

    assertThat(identity).isPresent();
    assertThat(identity.get().id()).isEqualTo("admin-center");
    assertThat(identity.get().permissions()).isEqualTo(Set.of("assert-end-user-context"));
    assertThat(identity.get().has("assert-end-user-context")).isTrue();
    assertThat(identity.get().has("other")).isFalse();
  }

  @Test
  void unknownKeyReturnsEmpty() {
    ClientRegistry registry =
        registry(
            """
            [{"id":"admin-center","keyHashes":["%s"],"permissions":[]}]
            """
                .formatted(hash(KEY_A)));

    assertThat(registry.authenticate(KEY_OTHER)).isEmpty();
    assertThat(registry.authenticate("")).isEmpty();
    assertThat(registry.authenticate(null)).isEmpty();
  }

  @Test
  void bothKeysAreValidDuringRotation() {
    ClientRegistry registry =
        registry(
            """
            [{"id":"admin-center","keyHashes":["%s","%s"],"permissions":[]}]
            """
                .formatted(hash(KEY_A), hash(KEY_B)));

    assertThat(registry.authenticate(KEY_A)).map(ClientIdentity::id).contains("admin-center");
    assertThat(registry.authenticate(KEY_B)).map(ClientIdentity::id).contains("admin-center");
  }

  @Test
  void eachClientHasItsOwnKeys() {
    ClientRegistry registry =
        registry(
            """
            [{"id":"admin-center","keyHashes":["%s"],"permissions":["assert-end-user-context"]},
             {"id":"reporting","keyHashes":["%s"],"permissions":[]}]
            """
                .formatted(hash(KEY_A), hash(KEY_B)));

    assertThat(registry.authenticate(KEY_A)).map(ClientIdentity::id).contains("admin-center");
    assertThat(registry.authenticate(KEY_B)).map(ClientIdentity::id).contains("reporting");
  }

  @Test
  void hashComparisonIsCaseInsensitiveOnHexDigits() {
    ClientRegistry registry =
        registry(
            """
            [{"id":"c","keyHashes":["sha256:%s"],"permissions":[]}]
            """
                .formatted(TestSecrets.sha256Hex(KEY_A).toUpperCase()));

    assertThat(registry.authenticate(KEY_A)).isPresent();
  }

  @Test
  void emptyConfigurationAuthenticatesNobody() {
    assertThat(registry("[]").authenticate(KEY_A)).isEmpty();
  }

  @Test
  void invalidConfigurationFailsFast() {
    String ok = hash(KEY_A);
    assertThatThrownBy(() -> registry("not json")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry("[{\"id\":\"\",\"keyHashes\":[\"" + ok + "\"]}]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry("[{\"id\":\"c\",\"keyHashes\":[]}]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                registry(
                    "[{\"id\":\"c\",\"keyHashes\":[\"%s\",\"%s\",\"%s\"]}]"
                        .formatted(ok, hash(KEY_B), hash(KEY_OTHER))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry("[{\"id\":\"c\",\"keyHashes\":[\"md5:abc\"]}]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry("[{\"id\":\"c\",\"keyHashes\":[\"sha256:zz\"]}]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                registry(
                    "[{\"id\":\"c\",\"keyHashes\":[\"%s\"]},{\"id\":\"c\",\"keyHashes\":[\"%s\"]}]"
                        .formatted(ok, hash(KEY_B))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void permissionsDefaultToNone() {
    ClientRegistry registry =
        registry("[{\"id\":\"c\",\"keyHashes\":[\"%s\"]}]".formatted(hash(KEY_A)));

    assertThat(registry.authenticate(KEY_A).orElseThrow().permissions()).isEmpty();
  }

  @Test
  void toStringNeverContainsHashes() {
    ClientRegistry registry =
        registry("[{\"id\":\"c\",\"keyHashes\":[\"%s\"]}]".formatted(hash(KEY_A)));

    assertThat(registry.toString()).doesNotContain(TestSecrets.sha256Hex(KEY_A));
  }
}
