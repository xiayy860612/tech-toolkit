package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.model.SessionToken;
import com.s2u2m.uam.domain.port.SessionStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 退出登录（FR-010）：只终止该会话标识对应的这一条会话，同账号以外的会话与用户状态都不变。
 *
 * <p>幂等：未知、已到期、已退出、空白或过长的标识一律静默成功——退出的目的是「这个标识此后不再有效」，它本来就无效也算达成。 经会话引用复用 {@link
 * SessionStore#terminateByRef}（含 {@code WAIT 1}）；不记录也不回显会话标识。退出不属于管理员写操作，不写 {@code uam.admin.write}
 * 日志。
 */
@Component
@Profile("!cli")
public class LogoutUseCase {

  /** 契约里会话标识的长度上限；更长的一定不是我们签发的。 */
  private static final int MAX_SESSION_ID_LENGTH = 128;

  private final SessionStore sessions;

  public LogoutUseCase(SessionStore sessions) {
    this.sessions = sessions;
  }

  public void logout(String sessionId) {
    if (sessionId == null || sessionId.isBlank() || sessionId.length() > MAX_SESSION_ID_LENGTH) {
      return;
    }
    sessions
        .find(SessionToken.hashOf(sessionId))
        .ifPresent(session -> sessions.terminateByRef(session.ref(), false));
  }
}
