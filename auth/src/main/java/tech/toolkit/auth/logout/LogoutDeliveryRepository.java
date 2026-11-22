package tech.toolkit.auth.logout;

import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The queue of back-channel logout notices ({@code auth_logout_delivery}). */
@Repository
public class LogoutDeliveryRepository {

  /** One notice that is due, with the address of its client. */
  public record Delivery(long id, String clientId, String sid, int attempts, String uri) {}

  private final JdbcTemplate jdbc;

  public LogoutDeliveryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Queues a notice for every client of the login that has a logout address and is enabled. */
  public int enqueueFor(String sid) {
    return jdbc.update(
        "insert into auth_logout_delivery (client_id, session_id)"
            + " select s.client_id, s.session_id from auth_sso_client s"
            + " join auth_client_ext e on e.client_id = s.client_id"
            + " where s.session_id = ? and e.enabled and e.backchannel_logout_uri is not null",
        sid);
  }

  /**
   * Locks the notices that are due. Another instance running the same query skips the locked rows,
   * so each notice is handled by exactly one instance; call inside a transaction.
   */
  public List<Delivery> claimDue(int limit) {
    return jdbc.query(
        "select d.id, d.client_id, d.session_id, d.attempts, e.backchannel_logout_uri"
            + " from auth_logout_delivery d"
            + " left join auth_client_ext e on e.client_id = d.client_id"
            + " where d.status = 'PENDING' and d.next_attempt_at <= now()"
            + " order by d.id limit ? for update of d skip locked",
        (rs, row) ->
            new Delivery(
                rs.getLong("id"),
                rs.getString("client_id"),
                rs.getString("session_id"),
                rs.getInt("attempts"),
                rs.getString("backchannel_logout_uri")),
        limit);
  }

  public void markDelivered(long id, int attempts) {
    jdbc.update(
        "update auth_logout_delivery set status = 'DELIVERED', attempts = ? where id = ?",
        attempts,
        id);
  }

  public void scheduleRetry(long id, int attempts, Duration delay) {
    jdbc.update(
        "update auth_logout_delivery set attempts = ?,"
            + " next_attempt_at = now() + (? * interval '1 second') where id = ?",
        attempts,
        delay.toSeconds(),
        id);
  }

  public void markGaveUp(long id, int attempts) {
    jdbc.update(
        "update auth_logout_delivery set status = 'GAVE_UP', attempts = ? where id = ?",
        attempts,
        id);
  }
}
