package com.s2u2m.uam.unit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.cli.GenerateServiceKeyCommand;
import com.s2u2m.uam.infrastructure.security.ClientRegistry;
import com.s2u2m.uam.support.TestSecrets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * T175：{@code generate-service-key --client <名称>}——32 字节密码学安全随机数、base64url（无填充）、前缀 {@code
 * uamk_}；标准输出只输出密钥一次； 标准错误给出对应的 {@code sha256:<hex>} 与 {@code UAM_CLIENTS} 片段；不连库、不连
 * Redis（命令没有任何依赖）。
 */
class GenerateServiceKeyCommandTest {

  private static final Pattern KEY_FORMAT = Pattern.compile("^uamk_[A-Za-z0-9_-]{43}$");
  private static final Pattern HASH_FORMAT = Pattern.compile("sha256:[0-9a-f]{64}");

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private int run(GenerateServiceKeyCommand command, String... args) {
    return command.run(
        List.of(args),
        new ByteArrayInputStream(new byte[0]),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private int run(String... args) {
    return run(new GenerateServiceKeyCommand(), args);
  }

  private String stdout() {
    return out.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return err.toString(StandardCharsets.UTF_8);
  }

  /** 记录请求了多少字节的确定性「随机数」：填 0、1、2 …，用来精确断言密钥的构成。 */
  private static final class RecordingRandom extends SecureRandom {
    final List<Integer> requested = new ArrayList<>();

    @Override
    public void nextBytes(byte[] bytes) {
      requested.add(bytes.length);
      for (int i = 0; i < bytes.length; i++) {
        bytes[i] = (byte) i;
      }
    }
  }

  @Test
  void keyIs32SecureRandomBytesBase64UrlWithoutPaddingAndPrefixed() {
    RecordingRandom random = new RecordingRandom();

    int code = run(new GenerateServiceKeyCommand(random), "--client", "admin-center");

    assertThat(code).isZero();
    assertThat(random.requested).containsExactly(32);
    byte[] expected = new byte[32];
    for (int i = 0; i < expected.length; i++) {
      expected[i] = (byte) i;
    }
    assertThat(stdout())
        .isEqualTo(
            "uamk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(expected) + "\n");
  }

  @Test
  void realKeysHaveTheDocumentedFormatAndDiffer() {
    run("--client", "admin-center");
    String first = stdout().strip();
    out.reset();
    run("--client", "admin-center");
    String second = stdout().strip();

    assertThat(first).matches(KEY_FORMAT);
    assertThat(second).matches(KEY_FORMAT);
    assertThat(first).isNotEqualTo(second);
    assertThat(Base64.getUrlDecoder().decode(first.substring("uamk_".length()))).hasSize(32);
  }

  @Test
  void stdoutHasOnlyTheKeyOnceWithASingleTrailingNewline() {
    run("--client", "admin-center");

    String key = stdout().strip();
    assertThat(stdout()).isEqualTo(key + "\n");
    assertThat(stdout().split("\n")).hasSize(1);
    assertThat(stderr()).doesNotContain(key);
  }

  @Test
  void stderrCarriesTheMatchingHashAndAPasteReadyClientsFragment() {
    run("--client", "admin-center");

    String key = stdout().strip();
    String hash = "sha256:" + TestSecrets.sha256Hex(key);
    assertThat(stderr()).contains(hash).contains("keyHashes");
    Matcher matcher = HASH_FORMAT.matcher(stderr());
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group()).isEqualTo(hash);

    // 片段是合法的 UAM_CLIENTS：能被注册表解析，且生成的密钥能通过它认证并拥有断言权限
    String fragment =
        stderr().lines().filter(line -> line.startsWith("[{")).findFirst().orElseThrow();
    ClientRegistry registry = ClientRegistry.fromJson(JsonMapper.builder().build(), fragment);
    var identity = registry.authenticate(key).orElseThrow();
    assertThat(identity.id()).isEqualTo("admin-center");
    assertThat(identity.permissions()).containsExactly("assert-end-user-context");
  }

  @Test
  void stderrExplainsThatRotationListsBothHashes() {
    run("--client", "admin-center");

    assertThat(stderr()).contains("轮换").contains("两个");
  }

  @Test
  void clientNamesAreValidated() {
    for (String bad :
        new String[] {
          "", " ", "has space", "quo\"te", "back\\slash", "a".repeat(64), "-lead", "中文", "a/b"
        }) {
      out.reset();
      err.reset();

      int code = run("--client", bad);

      assertThat(code).as("[%s]", bad).isEqualTo(2);
      assertThat(stdout()).as("[%s]", bad).isEmpty();
    }
    assertThat(run("--client", "reporting.v2_prod-1")).isZero();
    assertThat(run("--client", "a".repeat(63))).isZero();
  }

  @Test
  void usageErrorsReturnTwoAndNeverPrintAKey() {
    assertThat(run()).isEqualTo(2);
    assertThat(run("--client")).isEqualTo(2);
    assertThat(run("--client", "x", "--bogus", "y")).isEqualTo(2);
    assertThat(run("--client", "x", "extra")).isEqualTo(2);
    assertThat(stdout()).isEmpty();
    assertThat(stderr()).contains("参数错误");
  }

  @Test
  void commandNameIsGenerateServiceKey() {
    assertThat(new GenerateServiceKeyCommand().name()).isEqualTo("generate-service-key");
  }
}
