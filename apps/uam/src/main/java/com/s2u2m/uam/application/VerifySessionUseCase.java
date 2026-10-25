package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionToken;
import com.s2u2m.uam.domain.policy.SessionPolicy;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.SessionStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 校验会话标识并取得会话数据（FR-007）。
 *
 * <p>无效、过期、被取代、被终止一律 {@code session_invalid}；其中仅「通知窗口内被管理员强制下线」的 401 另带可选的 {@code reason:
 * terminated}（只按标识哈希查墓碑，不记录标识）；有效则按需续期——距上次写入超过续期间隔（60 秒）才写，避免每个页面请求都产生一次 Redis 写。
 * 过长或空白的标识同样视为无效而不是错误请求，让客户端只需区分「会话无效」与「服务不可用」。
 */
@Component
@Profile("!cli")
public class VerifySessionUseCase {

  /** 契约里会话标识的长度上限；更长的一定不是我们签发的。 */
  private static final int MAX_SESSION_ID_LENGTH = 128;

  private final SessionStore sessions;
  private final SessionPolicy policy;
  private final Clock clock;

  public VerifySessionUseCase(SessionStore sessions, SessionPolicy policy, Clock clock) {
    this.sessions = sessions;
    this.policy = policy;
    this.clock = clock;
  }

  public Session verify(String sessionId) {
    if (sessionId == null || sessionId.isBlank() || sessionId.length() > MAX_SESSION_ID_LENGTH) {
      throw invalid();
    }
    String hash = SessionToken.hashOf(sessionId);
    var now = clock.now();
    Session session =
        sessions
            .find(hash)
            .filter(found -> found.isActive(now))
            .orElseThrow(() -> endedWithoutSession(hash));
    if (!policy.shouldTouch(session.lastActiveAt(), now)) {
      return session;
    }
    return sessions.touch(hash, session.touched(now, policy)).orElseThrow(this::invalid);
  }

  /** 会话不存在：若它在通知窗口内被管理员强制下线则带 {@code reason: terminated}，否则（退出、到期、被取代、停用、未知）不带。 */
  private DomainException endedWithoutSession(String hash) {
    return sessions.wasTerminated(hash) ? DomainException.sessionTerminated() : invalid();
  }

  private DomainException invalid() {
    return new DomainException(ErrorCode.SESSION_INVALID, "会话无效或已结束");
  }
}
