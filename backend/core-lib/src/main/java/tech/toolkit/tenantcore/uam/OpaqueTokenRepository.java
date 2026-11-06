package tech.toolkit.tenantcore.uam;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public class OpaqueTokenRepository {

  private static final RowMapper<OpaqueToken> MAPPER =
      (rs, i) -> {
        OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
        return new OpaqueToken(
            rs.getString("sid"),
            rs.getObject("account_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
            revoked == null ? null : revoked.toInstant());
      };

  private static final String COLUMNS =
      "sid, account_id, user_id, tenant_id, created_at, last_seen_at, expires_at, revoked_at";

  private final JdbcClient jdbc;

  public OpaqueTokenRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  public void insert(byte[] tokenHash, OpaqueToken token) {
    jdbc.sql(
            "INSERT INTO uam_opaque_token(token_hash, sid, account_id, user_id, tenant_id,"
                + " created_at, last_seen_at, expires_at) VALUES (:hash, :sid, :account, :user,"
                + " :tenant, :created, :seen, :expires)")
        .param("hash", tokenHash)
        .param("sid", token.sid())
        .param("account", token.accountId())
        .param("user", token.userId())
        .param("tenant", token.tenantId())
        .param("created", at(token.createdAt()))
        .param("seen", at(token.lastSeenAt()))
        .param("expires", at(token.expiresAt()))
        .update();
  }

  public Optional<OpaqueToken> findByHash(byte[] tokenHash) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM uam_opaque_token WHERE token_hash = :hash")
        .param("hash", tokenHash)
        .query(MAPPER)
        .optional();
  }

  public Optional<OpaqueToken> findBySid(String sid) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM uam_opaque_token WHERE sid = :sid")
        .param("sid", sid)
        .query(MAPPER)
        .optional();
  }

  public void touch(byte[] tokenHash, Instant now) {
    jdbc.sql("UPDATE uam_opaque_token SET last_seen_at = :now WHERE token_hash = :hash")
        .param("now", at(now))
        .param("hash", tokenHash)
        .update();
  }

  public void revoke(byte[] tokenHash, Instant now) {
    jdbc.sql(
            "UPDATE uam_opaque_token SET revoked_at = :now"
                + " WHERE token_hash = :hash AND revoked_at IS NULL")
        .param("now", at(now))
        .param("hash", tokenHash)
        .update();
  }
}
