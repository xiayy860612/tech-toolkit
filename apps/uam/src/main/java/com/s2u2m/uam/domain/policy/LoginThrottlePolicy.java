package com.s2u2m.uam.domain.policy;

import com.s2u2m.uam.domain.model.LoginName;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 登录失败限制（FR-006）：同一「账号 + 来源地址」连续失败达到阈值（默认 5 次）后拒绝该来源一段时间（默认 15 分钟）；只限来源，不锁账号。
 *
 * <p>计数主体为 {@code SHA-256(规范化账号名 | 来源地址)}：账号不存在时同样计数，且键里不出现明文账号名。
 */
public final class LoginThrottlePolicy {

  private final int maxFailures;
  private final Duration block;

  public LoginThrottlePolicy(int maxFailures, Duration block) {
    this.maxFailures = maxFailures;
    this.block = block;
  }

  public int maxFailures() {
    return maxFailures;
  }

  public Duration block() {
    return block;
  }

  public boolean isBlocked(long failures) {
    return failures >= maxFailures;
  }

  public String subject(LoginName name, String sourceIp) {
    String source = sourceIp.strip().toLowerCase(Locale.ROOT);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((name.normalized() + "|" + source).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JDK 必须提供 SHA-256", e);
    }
  }
}
