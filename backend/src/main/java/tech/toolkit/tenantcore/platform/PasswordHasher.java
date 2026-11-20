package tech.toolkit.tenantcore.platform;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Argon2id 不可逆哈希（FR-006）。参数可配置并随哈希串保存，便于日后升级。 */
@Component
public class PasswordHasher {

  private final Argon2PasswordEncoder encoder;
  private final String dummyHash;

  public PasswordHasher(AdminCenterProperties.Password params) {
    this.encoder =
        new Argon2PasswordEncoder(
            16, 32, params.parallelism(), params.memoryKib(), params.iterations());
    this.dummyHash = encoder.encode("dummy-password-for-constant-time-checks");
  }

  @org.springframework.beans.factory.annotation.Autowired
  public PasswordHasher(AdminCenterProperties properties) {
    this(properties.password());
  }

  public String hash(String password) {
    return encoder.encode(password);
  }

  public boolean matches(String password, String hash) {
    return encoder.matches(password, hash);
  }

  /** 用户名不存在时仍做一次等价的哈希比较，避免通过耗时区分用户名是否存在（FR-007）。 */
  public boolean dummyMatch(String password) {
    encoder.matches(password, dummyHash);
    return false;
  }
}
