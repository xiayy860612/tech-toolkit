package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import java.util.Optional;

/** 会话存储（Redis）；会话只以 {@code SHA-256(会话标识)} 为键，明文标识从不入库。 */
public interface SessionStore {

  /** 写入新会话，并原子地顶掉同账号的旧会话（账号与会话一一对应）；返回被顶掉的旧会话引用。 */
  Optional<SessionRef> create(Session session, String tokenHash);

  Optional<Session> find(String tokenHash);

  /** 续期：把会话更新为 {@code touched}；会话已不存在则返回空，且不重建。 */
  Optional<Session> touch(String tokenHash, Session touched);

  /** 按会话引用终止；会话本来就不存在返回 {@code false}。 */
  boolean terminate(SessionRef ref);
}
