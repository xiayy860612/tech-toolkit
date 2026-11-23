package tech.toolkit.auth.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code auth_user}. Usernames are matched and stored in lower case. */
@Repository
public class UserRepository {

  private static final String COLUMNS =
      "id, username, password_hash, status, is_admin, must_change_password, failed_attempts,"
          + " locked_until, password_changed_at, created_at, updated_at";

  private static final RowMapper<User> MAPPER = UserRepository::map;

  private final JdbcTemplate jdbc;

  public UserRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<User> findByUsername(String username) {
    return jdbc
        .query(
            "select " + COLUMNS + " from auth_user where username = ?", MAPPER, normalize(username))
        .stream()
        .findFirst();
  }

  public Optional<User> findById(long id) {
    return jdbc.query("select " + COLUMNS + " from auth_user where id = ?", MAPPER, id).stream()
        .findFirst();
  }

  /** Inserts a new enabled user and returns its id. The hash must already be encoded. */
  public long insert(
      String username, String passwordHash, boolean admin, boolean mustChangePassword) {
    Long id =
        jdbc.queryForObject(
            "insert into auth_user (username, password_hash, is_admin, must_change_password)"
                + " values (?, ?, ?, ?) returning id",
            Long.class,
            normalize(username),
            passwordHash,
            admin,
            mustChangePassword);
    return id;
  }

  /**
   * Sets a new password hash and clears the lockout. The change time comes from the application
   * clock, the same one that stamps the tokens, because refresh tokens issued before it stop
   * working.
   */
  public void updatePassword(
      long id, String passwordHash, boolean mustChangePassword, java.time.Instant changedAt) {
    jdbc.update(
        "update auth_user set password_hash = ?, must_change_password = ?, password_changed_at = ?,"
            + " failed_attempts = 0, locked_until = null, updated_at = now() where id = ?",
        passwordHash,
        mustChangePassword,
        java.sql.Timestamp.from(changedAt),
        id);
  }

  public void setStatus(long id, User.Status status) {
    jdbc.update(
        "update auth_user set status = ?, updated_at = now() where id = ?", status.name(), id);
  }

  public void unlock(long id) {
    jdbc.update(
        "update auth_user set failed_attempts = 0, locked_until = null, updated_at = now()"
            + " where id = ?",
        id);
  }

  /** A page of users, by username prefix and status (both optional), oldest first. */
  public List<User> findPage(String usernamePrefix, User.Status status, int offset, int limit) {
    String wanted = status == null ? null : status.name();
    return jdbc.query(
        "select "
            + COLUMNS
            + " from auth_user where username like ? and (?::text is null or status = ?)"
            + " order by id limit ? offset ?",
        MAPPER,
        like(usernamePrefix),
        wanted,
        wanted,
        limit,
        offset);
  }

  public long count(String usernamePrefix, User.Status status) {
    String wanted = status == null ? null : status.name();
    Long total =
        jdbc.queryForObject(
            "select count(*) from auth_user where username like ? and (?::text is null or status = ?)",
            Long.class,
            like(usernamePrefix),
            wanted,
            wanted);
    return total == null ? 0 : total;
  }

  private static String like(String prefix) {
    String escaped =
        normalize(prefix).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return escaped + "%";
  }

  /**
   * The username format the database also enforces: 3 to 64 of lower-case letters, digits . _ -.
   */
  public static boolean isValidUsername(String normalized) {
    return normalized != null && normalized.matches("[a-z0-9._-]{3,64}");
  }

  /** Usernames are matched and stored in lower case, without surrounding blanks. */
  public static String normalize(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }

  private static User map(ResultSet rs, int row) throws SQLException {
    OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
    OffsetDateTime changedAt = rs.getObject("password_changed_at", OffsetDateTime.class);
    return new User(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("password_hash"),
        User.Status.valueOf(rs.getString("status")),
        rs.getBoolean("is_admin"),
        rs.getBoolean("must_change_password"),
        rs.getInt("failed_attempts"),
        lockedUntil == null ? null : lockedUntil.toInstant(),
        changedAt == null ? null : changedAt.toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
