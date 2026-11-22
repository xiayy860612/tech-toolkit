package tech.toolkit.auth.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
          + " locked_until, password_changed_at";

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

  static String normalize(String username) {
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
        changedAt == null ? null : changedAt.toInstant());
  }
}
