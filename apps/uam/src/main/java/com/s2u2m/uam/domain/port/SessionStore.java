package com.s2u2m.uam.domain.port;

import com.s2u2m.uam.domain.model.OnlineUserSummary;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会话存储（Redis）；会话只以 {@code SHA-256(会话标识)} 为键，明文标识从不入库。 */
public interface SessionStore {

  /** 写入新会话，并原子地顶掉同账号的旧会话（账号与会话一一对应）；返回被顶掉的旧会话引用。 */
  Optional<SessionRef> create(Session session, String tokenHash);

  Optional<Session> find(String tokenHash);

  /** 续期：把会话更新为 {@code touched}；会话已不存在则返回空，且不重建。 */
  Optional<Session> touch(String tokenHash, Session touched);

  /**
   * 按会话引用终止（强制下线、登录后复查回滚）：一次清理 {@code sess} / {@code sref} / {@code acct} 指针（仍指向它时）/ {@code
   * online}，随后 {@code WAIT 1}。只动这一条会话；会话本来就不存在返回 {@code false}。
   */
  boolean terminateByRef(SessionRef ref);

  /**
   * 终止这些账号当前的会话（用户停用时传入其全部账号）：一次清理 {@code sess} / {@code sref} / {@code acct} 指针 / {@code online}，
   * 随后 {@code WAIT 1}；没有会话的账号被忽略。返回实际终止的会话数。
   */
  int terminateAllForUser(Collection<UUID> accountIds);

  /**
   * 在线用户：先清理到期的在线索引条目，再按用户聚合仍有效的会话；按最近活动时间倒序、用户标识升序，保证分页稳定。
   *
   * @param now 当前时刻；有效到期不晚于它的会话不计入
   */
  List<OnlineUserSummary> listOnline(Instant now);

  /** 各账号当前的会话（即账号到会话的指针指向的那条），按传入账号的顺序；没有会话的账号不产生条目。 */
  List<Session> sessionsOfAccounts(Collection<UUID> accountIds);
}
