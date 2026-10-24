package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.domain.model.ClientIdentity;
import com.s2u2m.uam.infrastructure.security.ClientRegistry;
import com.s2u2m.uam.support.TestSecrets;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** T172：不停机轮换——同一客户端至多两个同时有效的密钥哈希；撤销（从配置里删掉）其一后旧密钥被拒、新密钥有效；哈希数量不合法时拒绝启动，且报错清楚、不回显哈希。 */
class ClientRegistryRotationTest {

  // 明显是假的测试密钥
  private static final String OLD_KEY = "uamk_test_fake_rotation_old";
  private static final String NEW_KEY = "uamk_test_fake_rotation_new";
  private static final String THIRD_KEY = "uamk_test_fake_rotation_third";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static String hash(String key) {
    return "sha256:" + TestSecrets.sha256Hex(key);
  }

  /** 一个 {@code admin-center} 客户端，配置给定密钥的哈希（0 到 n 个）。 */
  private static ClientRegistry registryWithKeys(String... keys) {
    return ClientRegistry.fromJson(MAPPER, config("admin-center", keys));
  }

  private static String config(String clientId, String... keys) {
    String hashes =
        Stream.of(keys).map(key -> "\"" + hash(key) + "\"").collect(Collectors.joining(","));
    return "[{\"id\":\"%s\",\"keyHashes\":[%s],\"permissions\":[\"assert-end-user-context\"]}]"
        .formatted(clientId, hashes);
  }

  @Test
  void twoHashesAreBothValidAtTheSameTime() {
    ClientRegistry registry = registryWithKeys(OLD_KEY, NEW_KEY);

    assertThat(registry.authenticate(OLD_KEY)).map(ClientIdentity::id).contains("admin-center");
    assertThat(registry.authenticate(NEW_KEY)).map(ClientIdentity::id).contains("admin-center");
    assertThat(registry.authenticate(THIRD_KEY)).isEmpty();
  }

  @Test
  void revokingTheOldHashRejectsTheOldKeyAndKeepsTheNewOne() {
    ClientRegistry revoked = registryWithKeys(NEW_KEY);

    assertThat(revoked.authenticate(OLD_KEY)).isEmpty();
    assertThat(revoked.authenticate(NEW_KEY)).isPresent();
  }

  @Test
  void aFullRotationCycleNeverLeavesAWindowWithoutAValidKey() {
    // 轮换的三步配置：只有旧 → 新旧并存 → 只有新；每一步，调用方持有的那把钥匙都有效
    ClientRegistry before = registryWithKeys(OLD_KEY);
    ClientRegistry during = registryWithKeys(OLD_KEY, NEW_KEY);
    ClientRegistry after = registryWithKeys(NEW_KEY);

    assertThat(before.authenticate(OLD_KEY)).isPresent();
    assertThat(before.authenticate(NEW_KEY)).isEmpty();
    // 新旧并存期间：Admin Center 仍用旧钥匙或已切到新钥匙，都不失败
    assertThat(during.authenticate(OLD_KEY)).isPresent();
    assertThat(during.authenticate(NEW_KEY)).isPresent();
    assertThat(after.authenticate(NEW_KEY)).isPresent();
    assertThat(after.authenticate(OLD_KEY)).isEmpty();
  }

  @Test
  void moreThanTwoHashesRefuseToStartWithAClearMessage() {
    assertThatThrownBy(() -> registryWithKeys(OLD_KEY, NEW_KEY, THIRD_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("admin-center")
        .hasMessageContaining("1–2")
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .doesNotContain(TestSecrets.sha256Hex(OLD_KEY))
                    .doesNotContain(TestSecrets.sha256Hex(NEW_KEY))
                    .doesNotContain(TestSecrets.sha256Hex(THIRD_KEY)));
  }

  @Test
  void noHashesRefuseToStartWithAClearMessage() {
    assertThatThrownBy(() -> registryWithKeys())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("admin-center")
        .hasMessageContaining("1–2");
    assertThatThrownBy(
            () ->
                ClientRegistry.fromJson(
                    MAPPER,
                    "[{\"id\":\"admin-center\",\"permissions\":[\"assert-end-user-context\"]}]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("admin-center");
  }

  @Test
  void theSameHashListedTwiceForOneClientIsRefused() {
    // 两个相同的哈希只是一把钥匙，却占满了「至多两把」：多半是复制粘贴错误，直接拒绝
    assertThatThrownBy(() -> registryWithKeys(OLD_KEY, OLD_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("admin-center")
        .hasMessageContaining("重复");
  }

  @Test
  void theSameHashForTwoClientsIsRefusedBecauseTheIdentityWouldBeAmbiguous() {
    String json =
        "[{\"id\":\"a\",\"keyHashes\":[\"%s\"]},{\"id\":\"b\",\"keyHashes\":[\"%s\"]}]"
            .formatted(hash(OLD_KEY), hash(OLD_KEY));

    assertThatThrownBy(() -> ClientRegistry.fromJson(MAPPER, json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复");
  }
}
