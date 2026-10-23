package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.infrastructure.logging.LogRedaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T020：统一脱敏层，保证密码、哈希、会话标识、服务密钥永不进入日志。 */
class LogRedactionTest {

  private static final String MASK = LogRedaction.MASK;

  @Test
  void nullAndPlainTextPassThrough() {
    assertThat(LogRedaction.redact(null)).isNull();
    assertThat(LogRedaction.redact("cannot_disable_self")).isEqualTo("cannot_disable_self");
    assertThat(LogRedaction.redact("用户已停用")).isEqualTo("用户已停用");
    assertThat(LogRedaction.redact("3f2b8c1e-6d7a-4b9e-8c11-0a1b2c3d4e5f"))
        .isEqualTo("3f2b8c1e-6d7a-4b9e-8c11-0a1b2c3d4e5f");
  }

  @Test
  void serviceKeysAreMasked() {
    String redacted = LogRedaction.redact("denied uamk_test_fake_key_do_not_use_anywhere here");

    assertThat(redacted).doesNotContain("uamk_test").contains(MASK).startsWith("denied ");
  }

  @Test
  void bearerCredentialsAreMasked() {
    assertThat(LogRedaction.redact("Authorization: Bearer abc.def-ghi_123"))
        .doesNotContain("abc.def")
        .contains(MASK);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "$argon2id$v=19$m=16384,t=2,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g",
        "{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA",
        "{bcrypt}$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234",
        "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
      })
  void passwordAndKeyHashesAreMasked(String hash) {
    String redacted = LogRedaction.redact("value " + hash + " end");

    assertThat(redacted).doesNotContain("argon2id").doesNotContain("$2a$").doesNotContain("2cf24d");
    assertThat(redacted).contains(MASK).startsWith("value ").endsWith(" end");
  }

  @Test
  void keyValueSecretsAreMasked() {
    String redacted =
        LogRedaction.redact(
            "password=Sup3rSecret currentPassword=old1 newPassword: n3w sessionId=abc123 "
                + "passwordHash=zzz serviceKey=k1 \"password\":\"quoted-secret\" ok=fine");

    assertThat(redacted)
        .doesNotContain("Sup3rSecret")
        .doesNotContain("old1")
        .doesNotContain("n3w")
        .doesNotContain("abc123")
        .doesNotContain("zzz")
        .doesNotContain("k1 ")
        .doesNotContain("quoted-secret")
        .contains("ok=fine");
  }

  @Test
  void longOpaqueTokensLikeSessionIdsAreMasked() {
    // 256 位随机数的 base64url 为 43 个字符
    String sessionId = "dGhpc19pc19hX2Zha2Vfc2Vzc2lvbl9pZF9mb3JfdGVzdGluZ19v";

    assertThat(LogRedaction.redact("sid " + sessionId)).isEqualTo("sid " + MASK);
  }

  @Test
  void sensitiveKeyNamesAreRecognisedCaseInsensitively() {
    for (String key :
        new String[] {
          "password",
          "Password",
          "currentPassword",
          "newPassword",
          "passwordHash",
          "password_hash",
          "sessionId",
          "session_id",
          "serviceKey",
          "authorization",
          "token"
        }) {
      assertThat(LogRedaction.isSensitiveKey(key)).as(key).isTrue();
    }
    for (String key : new String[] {"sessionRef", "userId", "action", "reason", "clientId"}) {
      assertThat(LogRedaction.isSensitiveKey(key)).as(key).isFalse();
    }
  }

  @Test
  void valueForMasksByKeyName() {
    assertThat(LogRedaction.valueFor("password", "hunter2")).isEqualTo(MASK);
    assertThat(LogRedaction.valueFor("reason", "cannot_disable_self"))
        .isEqualTo("cannot_disable_self");
    assertThat(LogRedaction.valueFor("reason", "password=hunter2")).doesNotContain("hunter2");
    assertThat(LogRedaction.valueFor("reason", null)).isNull();
  }
}
