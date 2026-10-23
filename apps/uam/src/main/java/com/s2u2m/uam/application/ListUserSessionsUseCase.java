package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.port.AccountRepository;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.SessionStore;
import com.s2u2m.uam.domain.port.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 某用户当前的全部有效会话（FR-029、FR-032）：用户的每个账号各至多一条，即账号指针指向的会话。
 *
 * <p>{@code current} 由操作人的会话引用（请求头 {@code X-Operator-Session-Ref}，可缺省）与会话引用相等得出；已过有效到期的会话不返回。用户不存在抛
 * {@code user_not_found}；用户没有会话时返回空列表。
 */
@Component
@Profile("!cli")
public class ListUserSessionsUseCase {

  /** 一条会话及其是否为操作人自己正在使用的会话。 */
  public record Item(Session session, boolean current) {}

  private final UserRepository users;
  private final AccountRepository accounts;
  private final SessionStore sessions;
  private final Clock clock;

  public ListUserSessionsUseCase(
      UserRepository users, AccountRepository accounts, SessionStore sessions, Clock clock) {
    this.users = users;
    this.accounts = accounts;
    this.sessions = sessions;
    this.clock = clock;
  }

  /**
   * @param operatorSessionRef 操作人自己的会话引用；{@code null} 表示未提供，所有会话的 {@code current} 都为 {@code false}
   */
  public List<Item> list(UUID userId, UUID operatorSessionRef) {
    users
        .findById(userId)
        .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
    List<UUID> accountIds = accounts.findByUserId(userId).stream().map(Account::id).toList();
    Instant now = clock.now();
    return sessions.sessionsOfAccounts(accountIds).stream()
        .filter(session -> session.isActive(now))
        .map(session -> new Item(session, session.ref().value().equals(operatorSessionRef)))
        .toList();
  }
}
