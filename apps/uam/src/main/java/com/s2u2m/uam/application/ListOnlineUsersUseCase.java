package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.model.OnlineUserSummary;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.SessionStore;
import com.s2u2m.uam.domain.port.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 分页列出在线用户（至少有一个有效会话的用户，FR-028、FR-032）。
 *
 * <p>会话存储给出已清理、已按用户聚合并按最近活动倒序排好的列表；本用例按页切分，再用一次批量查询回 PostgreSQL 取该页的用户名（权威来源，不用会话里的快照）。
 * 分页参数与用户列表同口径：越界返回 {@code validation_failed}。
 */
@Component
@Profile("!cli")
public class ListOnlineUsersUseCase {

  public static final int DEFAULT_SIZE = ListUsersUseCase.DEFAULT_SIZE;
  public static final int MAX_SIZE = ListUsersUseCase.MAX_SIZE;

  /** 一行在线用户。 */
  public record Item(UUID userId, String userName, int sessionCount, Instant lastActiveAt) {}

  /** 一页在线用户；{@code total} 为在线用户总数。 */
  public record Result(List<Item> items, int page, int size, long total) {}

  private final SessionStore sessions;
  private final UserRepository users;
  private final Clock clock;

  public ListOnlineUsersUseCase(SessionStore sessions, UserRepository users, Clock clock) {
    this.sessions = sessions;
    this.users = users;
    this.clock = clock;
  }

  public Result list(int page, int size) {
    validate(page, size);
    List<OnlineUserSummary> online = sessions.listOnline(clock.now());
    long from = (long) (page - 1) * size;
    if (from >= online.size()) {
      return new Result(List.of(), page, size, online.size());
    }
    List<OnlineUserSummary> pageRows =
        online.subList((int) from, (int) Math.min(online.size(), from + size));
    Map<UUID, String> names =
        users.findNames(pageRows.stream().map(OnlineUserSummary::userId).toList());
    List<Item> items = new ArrayList<>();
    for (OnlineUserSummary row : pageRows) {
      items.add(
          new Item(
              row.userId(),
              names.getOrDefault(row.userId(), row.userId().toString()),
              row.sessionCount(),
              row.lastActiveAt()));
    }
    return new Result(items, page, size, online.size());
  }

  private static void validate(int page, int size) {
    List<FieldError> errors = new ArrayList<>();
    if (page < 1) {
      errors.add(new FieldError("page", "invalid"));
    }
    if (size < 1 || size > MAX_SIZE) {
      errors.add(new FieldError("size", "invalid"));
    }
    if (!errors.isEmpty()) {
      throw DomainException.validationFailed(errors);
    }
  }
}
