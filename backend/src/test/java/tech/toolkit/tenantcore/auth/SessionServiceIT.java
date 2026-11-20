package tech.toolkit.tenantcore.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.MutableClock;

class SessionServiceIT extends AbstractPostgresIT {

  private static final String CHROME_MAC =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/130.0.0.0 Safari/537.36";

  @Autowired SessionService sessions;
  @Autowired MutableClock clock;
  @Autowired TransactionTemplate tx;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

  @BeforeEach
  void resetClock() {
    clock.reset();
  }

  private static final java.util.concurrent.atomic.AtomicLong NEXT_USER =
      new java.util.concurrent.atomic.AtomicLong(1_000);

  private SessionService.Created login(long user) {
    return TenantContext.callAs(
        0L,
        () -> {
          try {
            return sessions.create(
                user,
                LoginType.USERNAME_PASSWORD,
                InetAddress.getByName("203.0.113.9"),
                CHROME_MAC);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private Optional<Session> resolve(String token) {
    return TenantContext.callAs(0L, () -> sessions.resolve(token));
  }

  private Optional<Session> byId(UUID id) {
    return TenantContext.callAs(0L, () -> sessions.validate(id));
  }

  @Test
  void tokenIsRandomAndOnlyItsSha256IsStored() throws Exception {
    SessionService.Created a = login(NEXT_USER.incrementAndGet());
    SessionService.Created b = login(NEXT_USER.incrementAndGet());
    assertThat(a.token()).isNotEqualTo(b.token()).hasSizeGreaterThanOrEqualTo(43);

    byte[] expected = MessageDigest.getInstance("SHA-256").digest(a.token().getBytes());
    Map<String, Object> row =
        owner.queryForMap("select * from session where id = ?", a.session().getId());
    assertThat((byte[]) row.get("token_hash")).isEqualTo(expected);
    assertThat(row.values().stream().map(String::valueOf).toList())
        .noneMatch(v -> v.contains(a.token()));
  }

  @Test
  void storesIpUserAgentAndDerivedDevice() {
    SessionService.Created c = login(NEXT_USER.incrementAndGet());
    Map<String, Object> row =
        owner.queryForMap("select * from session where id = ?", c.session().getId());
    assertThat(row.get("device")).isEqualTo("Chrome 130 / macOS");
    assertThat(String.valueOf(row.get("ip"))).isEqualTo("203.0.113.9");
    assertThat(row.get("user_agent")).isEqualTo(CHROME_MAC);
  }

  @Test
  void resolveReturnsActiveSessionAndRejectsUnknownTokens() {
    SessionService.Created c = login(NEXT_USER.incrementAndGet());
    assertThat(resolve(c.token())).get().extracting(Session::getId).isEqualTo(c.session().getId());
    assertThat(resolve("not-a-real-token")).isEmpty();
    assertThat(resolve(null)).isEmpty();
  }

  @Test
  void lastSeenIsWrittenAtMostOncePerMinute() {
    SessionService.Created c = login(NEXT_USER.incrementAndGet());
    UUID id = c.session().getId();
    var created =
        owner.queryForObject(
            "select last_seen_at from session where id=?", java.sql.Timestamp.class, id);

    clock.advance(Duration.ofSeconds(30));
    resolve(c.token());
    assertThat(
            owner.queryForObject(
                "select last_seen_at from session where id=?", java.sql.Timestamp.class, id))
        .isEqualTo(created);

    clock.advance(Duration.ofSeconds(40));
    resolve(c.token());
    assertThat(
            owner.queryForObject(
                "select last_seen_at from session where id=?", java.sql.Timestamp.class, id))
        .isAfter(created);
  }

  @Test
  void idleTimeoutIsSlidingThirtyMinutes() {
    SessionService.Created c = login(NEXT_USER.incrementAndGet());
    clock.advance(Duration.ofMinutes(20));
    assertThat(resolve(c.token())).isPresent(); // 续期
    clock.advance(Duration.ofMinutes(20));
    assertThat(resolve(c.token())).isPresent(); // 距上次活跃 20 分钟，仍有效
    clock.advance(Duration.ofMinutes(31));
    assertThat(resolve(c.token())).isEmpty(); // 空闲超过 30 分钟
    assertThat(byId(c.session().getId())).isEmpty();
  }

  @Test
  void absoluteTimeoutIsEightHoursEvenWhenActive() {
    SessionService.Created c = login(NEXT_USER.incrementAndGet());
    for (int i = 0; i < 23; i++) {
      clock.advance(Duration.ofMinutes(20)); // 持续活跃，累计 7 小时 40 分
      assertThat(resolve(c.token())).isPresent();
    }
    clock.advance(Duration.ofMinutes(30)); // 累计 8 小时 10 分
    assertThat(resolve(c.token())).isEmpty();
  }

  @Test
  void revokeByIdMakesTheSessionInvalidAndRecordsTheReason() {
    SessionService.Created c = login(NEXT_USER.incrementAndGet());
    assertThat(
            TenantContext.callAs(
                0L, () -> sessions.revoke(c.session().getId(), RevokeReason.LOGOUT)))
        .isTrue();
    assertThat(resolve(c.token())).isEmpty();
    assertThat(byId(c.session().getId())).isEmpty();
    assertThat(
            owner.queryForObject(
                "select revoke_reason from session where id=?", String.class, c.session().getId()))
        .isEqualTo("LOGOUT");
    // 重复撤销是幂等的
    assertThat(
            TenantContext.callAs(
                0L, () -> sessions.revoke(c.session().getId(), RevokeReason.LOGOUT)))
        .isFalse();
  }

  @Test
  void revokeByUserAndExceptCurrent() {
    long user = NEXT_USER.incrementAndGet();
    long otherUser = NEXT_USER.incrementAndGet();
    SessionService.Created s1 = login(user);
    SessionService.Created s2 = login(user);
    SessionService.Created s3 = login(otherUser);

    int others =
        TenantContext.callAs(
            0L,
            () ->
                sessions.revokeOthersOfUser(
                    user, s1.session().getId(), RevokeReason.PASSWORD_CHANGED));
    assertThat(others).isEqualTo(1);
    assertThat(resolve(s1.token())).isPresent();
    assertThat(resolve(s2.token())).isEmpty();
    assertThat(resolve(s3.token())).isPresent();

    int byUser =
        TenantContext.callAs(0L, () -> sessions.revokeAllOfUser(user, RevokeReason.USER_DISABLED));
    assertThat(byUser).isEqualTo(1);
    assertThat(resolve(s1.token())).isEmpty();
    assertThat(resolve(s3.token())).isPresent();
    assertThat(
            owner.queryForObject(
                "select revoke_reason from session where id=?", String.class, s1.session().getId()))
        .isEqualTo("USER_DISABLED");
  }

  @Test
  void sessionsOfAnotherTenantAreInvisible() {
    SessionService.Created c = login(NEXT_USER.incrementAndGet());
    assertThat(TenantContext.callAs(1L, () -> sessions.resolve(c.token()))).isEmpty();
    assertThat(TenantContext.callAs(1L, () -> sessions.validate(c.session().getId()))).isEmpty();
  }

  @Test
  void purgeRemovesOldRevokedAndExpiredSessionsAcrossTenants() {
    UUID oldRevoked = UUID.randomUUID();
    UUID oldExpired = UUID.randomUUID();
    UUID recentRevoked = UUID.randomUUID();
    String insert =
        "insert into session(id, tenant_id, token_hash, user_id, last_seen_at,"
            + " absolute_expires_at, revoked_at) values (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)";
    owner.update(
        insert,
        oldRevoked,
        5L,
        UUID.randomUUID().toString().getBytes(),
        NEXT_USER.incrementAndGet(),
        "2000-01-01T00:00:00Z",
        "2100-01-01T00:00:00Z",
        "2000-01-01T00:00:00Z");
    owner.update(
        insert,
        oldExpired,
        6L,
        UUID.randomUUID().toString().getBytes(),
        NEXT_USER.incrementAndGet(),
        "2000-01-01T00:00:00Z",
        "2000-01-02T00:00:00Z",
        null);
    owner.update(
        insert,
        recentRevoked,
        7L,
        UUID.randomUUID().toString().getBytes(),
        NEXT_USER.incrementAndGet(),
        java.time.Instant.now().toString(),
        "2100-01-01T00:00:00Z",
        java.time.Instant.now().toString());

    assertThat(sessions.purge()).isGreaterThanOrEqualTo(2);

    List<UUID> left =
        owner.queryForList(
            "select id from session where id in (?, ?, ?)",
            UUID.class,
            oldRevoked,
            oldExpired,
            recentRevoked);
    assertThat(left).containsExactly(recentRevoked);
  }
}
