package tech.toolkit.auth.session;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Which clients one SSO login has served ({@code auth_sso_client}), so logout can notify them. */
@Repository
public class SsoClientRepository {

  private final JdbcTemplate jdbc;

  public SsoClientRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Records that the login {@code sid} was used with the client, whatever its internal id is. */
  public void record(String sid, String registeredClientId) {
    jdbc.update(
        "insert into auth_sso_client (session_id, client_id)"
            + " select ?, client_id from oauth2_registered_client where id = ?"
            + " on conflict do nothing",
        sid,
        registeredClientId);
  }

  public List<String> clientsOf(String sid) {
    return jdbc.queryForList(
        "select client_id from auth_sso_client where session_id = ? order by client_id",
        String.class,
        sid);
  }

  public int deleteBySession(String sid) {
    return jdbc.update("delete from auth_sso_client where session_id = ?", sid);
  }
}
