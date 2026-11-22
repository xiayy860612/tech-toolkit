package tech.toolkit.auth.client;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code auth_client_ext}. */
@Repository
public class ClientExtRepository {

  private final JdbcTemplate jdbc;

  public ClientExtRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ClientExt> find(String clientId) {
    return jdbc
        .query(
            "select client_id, enabled, backchannel_logout_uri, post_logout_redirect_uris,"
                + " access_token_ttl_seconds, refresh_token_ttl_seconds"
                + " from auth_client_ext where client_id = ?",
            (rs, row) ->
                new ClientExt(
                    rs.getString("client_id"),
                    rs.getBoolean("enabled"),
                    rs.getString("backchannel_logout_uri"),
                    rs.getString("post_logout_redirect_uris"),
                    (Integer) rs.getObject("access_token_ttl_seconds"),
                    (Integer) rs.getObject("refresh_token_ttl_seconds")),
            clientId)
        .stream()
        .findFirst();
  }

  public void upsert(ClientExt ext) {
    jdbc.update(
        "insert into auth_client_ext (client_id, enabled, backchannel_logout_uri,"
            + " post_logout_redirect_uris, access_token_ttl_seconds, refresh_token_ttl_seconds)"
            + " values (?, ?, ?, ?, ?, ?)"
            + " on conflict (client_id) do update set enabled = excluded.enabled,"
            + " backchannel_logout_uri = excluded.backchannel_logout_uri,"
            + " post_logout_redirect_uris = excluded.post_logout_redirect_uris,"
            + " access_token_ttl_seconds = excluded.access_token_ttl_seconds,"
            + " refresh_token_ttl_seconds = excluded.refresh_token_ttl_seconds",
        ext.clientId(),
        ext.enabled(),
        ext.backchannelLogoutUri(),
        ext.postLogoutRedirectUris(),
        ext.accessTokenTtlSeconds(),
        ext.refreshTokenTtlSeconds());
  }
}
