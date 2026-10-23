package com.s2u2m.uam.infrastructure.jdbc;

import com.s2u2m.uam.domain.model.PageResult;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.model.UserWithAccountCount;
import com.s2u2m.uam.domain.port.UserRepository;
import com.s2u2m.uam.domain.port.UserStatusLock;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 用户仓储（显式 SQL）与用户状态变更锁。{@code updated_at} 没有数据库默认值，插入与状态更新时显式取 {@code now()}；状态变更锁是 PostgreSQL
 * 的事务级咨询锁 （{@code pg_advisory_xact_lock}），随事务结束释放。
 */
@Repository
public class JdbcUserRepository implements UserRepository, UserStatusLock {

  /**
   * 搜索命中的用户标识：用户名命中 ∪ 账号名命中。两侧各自是可用 {@code pg_trgm} GIN 索引的 {@code ILIKE '%q%'}，用 {@code UNION} 而不是
   * {@code OR + EXISTS}，让两个索引都能被位图扫描使用（{@code :pattern} 由 {@link #likePattern} 生成）。
   */
  public static final String MATCHED_USER_IDS_SQL =
      "SELECT id FROM app_user WHERE name ILIKE :pattern ESCAPE '\\'"
          + " UNION SELECT user_id FROM account WHERE login_name_norm ILIKE :pattern ESCAPE '\\'";

  private static final String SELECT_PAGE =
      "SELECT u.id, u.name, u.status, u.created_at,"
          + " (SELECT count(*) FROM account a WHERE a.user_id = u.id) AS account_count"
          + " FROM app_user u";
  private static final String ORDER_AND_PAGE =
      " ORDER BY u.created_at DESC, u.id LIMIT :limit OFFSET :offset";

  /** 状态变更锁的键（全局唯一的一把锁；取值任意，只要不与同库其它咨询锁冲突）。 */
  static final long STATUS_LOCK_KEY = 0x55414D5F53544154L;

  private final JdbcClient jdbc;

  public JdbcUserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<User> findById(UUID id) {
    return jdbc.sql("SELECT id, name, status, created_at FROM app_user WHERE id = :id")
        .param("id", id)
        .query(JdbcUserRepository::map)
        .optional();
  }

  @Override
  public Map<UUID, String> findNames(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> names = new HashMap<>();
    jdbc.sql("SELECT id, name FROM app_user WHERE id IN (:ids)")
        .param("ids", ids)
        .query(
            rs -> {
              names.put(rs.getObject("id", UUID.class), rs.getString("name"));
            });
    return names;
  }

  @Override
  public void insert(User user) {
    jdbc.sql(
            "INSERT INTO app_user (id, name, status, created_at, updated_at)"
                + " VALUES (:id, :name, :status, :createdAt, now())")
        .param("id", user.id())
        .param("name", user.name())
        .param("status", user.status().name())
        .param("createdAt", OffsetDateTime.ofInstant(user.createdAt(), ZoneOffset.UTC))
        .update();
  }

  @Override
  public long count() {
    return jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
  }

  @Override
  public long countActive() {
    return jdbc.sql("SELECT count(*) FROM app_user WHERE status = 'ACTIVE'")
        .query(Long.class)
        .single();
  }

  @Override
  public boolean updateStatus(UUID id, UserStatus status) {
    return jdbc.sql(
                "UPDATE app_user SET status = :status, updated_at = now()"
                    + " WHERE id = :id AND status <> :status")
            .param("id", id)
            .param("status", status.name())
            .update()
        > 0;
  }

  @Override
  public void lockStatusChanges() {
    jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", STATUS_LOCK_KEY).query(rs -> {});
  }

  @Override
  public PageResult<UserWithAccountCount> search(String query, int page, int size) {
    long offset = (long) (page - 1) * size;
    if (query == null) {
      List<UserWithAccountCount> items =
          jdbc.sql(SELECT_PAGE + ORDER_AND_PAGE)
              .param("limit", size)
              .param("offset", offset)
              .query(JdbcUserRepository::mapListed)
              .list();
      return new PageResult<>(items, count());
    }
    String pattern = likePattern(query);
    List<UserWithAccountCount> items =
        jdbc.sql(
                "WITH matched AS ("
                    + MATCHED_USER_IDS_SQL
                    + ") "
                    + SELECT_PAGE
                    + " JOIN matched m ON m.id = u.id"
                    + ORDER_AND_PAGE)
            .param("pattern", pattern)
            .param("limit", size)
            .param("offset", offset)
            .query(JdbcUserRepository::mapListed)
            .list();
    long total =
        jdbc.sql("SELECT count(*) FROM (" + MATCHED_USER_IDS_SQL + ") matched")
            .param("pattern", pattern)
            .query(Long.class)
            .single();
    return new PageResult<>(items, total);
  }

  /** 包含匹配的 {@code LIKE} 模式；用户输入里的 {@code \}、{@code %}、{@code _} 转义，按字面匹配。 */
  static String likePattern(String query) {
    return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  private static UserWithAccountCount mapListed(ResultSet rs, int row) throws SQLException {
    return new UserWithAccountCount(map(rs, row), rs.getLong("account_count"));
  }

  private static User map(ResultSet rs, int row) throws SQLException {
    return new User(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        UserStatus.valueOf(rs.getString("status")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
