package tech.toolkit.tenantcore.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 校准默认 Argon2id 参数：单次哈希应在 100–400ms，才能保证登录 p95 ≤ 1 秒（plan Performance Goals）。 依赖机器性能，默认不在 CI 主流程运行。
 */
@Tag("performance")
class PasswordHasherBenchmarkTest {

  @Test
  void defaultParametersCostBetween100And400Milliseconds() {
    PasswordHasher hasher = new PasswordHasher(new AdminCenterProperties.Password(49152, 4, 1));
    hasher.hash("warm-up");
    long start = System.nanoTime();
    for (int i = 0; i < 5; i++) {
      hasher.hash("Str0ng-Passw0rd");
    }
    Duration perHash = Duration.ofNanos((System.nanoTime() - start) / 5);
    assertThat(perHash).isBetween(Duration.ofMillis(100), Duration.ofMillis(400));
  }
}
