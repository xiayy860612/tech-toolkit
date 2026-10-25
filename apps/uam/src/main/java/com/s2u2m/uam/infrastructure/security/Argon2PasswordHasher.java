package com.s2u2m.uam.infrastructure.security;

import com.s2u2m.uam.domain.port.PasswordHasher;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id 密码哈希（research R10）：{@link DelegatingPasswordEncoder}，默认 {@code
 * argon2@SpringSecurity_v5_8}，哈希自带算法标识， 旧算法 / 旧参数的哈希仍可校验，登录成功后由 {@link #needsUpgrade} 触发重新哈希。
 *
 * <p>伪哈希在构造时用同一编码器生成，与真哈希参数、成本完全一致；它是一次随机明文的哈希，没有任何密码能匹配。
 */
@Component
public class Argon2PasswordHasher implements PasswordHasher {

  private static final String CURRENT_ID = "argon2@SpringSecurity_v5_8";

  private final DelegatingPasswordEncoder encoder;
  private final String dummyHash;

  public Argon2PasswordHasher() {
    Map<String, PasswordEncoder> encoders = new HashMap<>();
    encoders.put(CURRENT_ID, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
    encoders.put("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_2());
    this.encoder = new DelegatingPasswordEncoder(CURRENT_ID, encoders);
    this.dummyHash = encoder.encode(UUID.randomUUID().toString());
  }

  @Override
  public String hash(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  @Override
  public boolean matches(String rawPassword, String passwordHash) {
    return encoder.matches(rawPassword, passwordHash);
  }

  @Override
  public boolean needsUpgrade(String passwordHash) {
    return encoder.upgradeEncoding(passwordHash);
  }

  @Override
  public String dummyHash() {
    return dummyHash;
  }
}
