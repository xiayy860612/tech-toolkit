package tech.toolkit.auth.account;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * argon2id for passwords and client secrets (FR-016). The id prefix in stored values keeps the
 * algorithm replaceable later.
 */
@Configuration
public class PasswordConfig {

  static final String ARGON2 = "argon2@SpringSecurity_v5_8";

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new DelegatingPasswordEncoder(
        ARGON2, Map.of(ARGON2, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
  }
}
