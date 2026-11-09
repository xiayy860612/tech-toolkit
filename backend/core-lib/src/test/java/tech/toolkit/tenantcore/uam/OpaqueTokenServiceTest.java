package tech.toolkit.tenantcore.uam;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tech.toolkit.tenantcore.common.IdGenerator;
import tech.toolkit.tenantcore.support.PostgresTestBase;
import tech.toolkit.tenantcore.uam.OpaqueTokenService.IssuedToken;

class OpaqueTokenServiceTest extends PostgresTestBase {

  private final MutableClock clock = new MutableClock();
  private final JdbcClient owner = JdbcClient.create(ownerDataSource());
  private final OpaqueTokenService tokens =
      new OpaqueTokenService(
          new OpaqueTokenRepository(JdbcClient.create(appDataSource())), new IdGenerator(), clock);

  private IssuedToken issue() {
    Bootstrapped b = bootstrap(core(clock), "acme", "admin1");
    return tokens.issue(b.account().id(), b.user().id(), b.tenant().id());
  }

  @Test
  void issuesAPrefixedTokenAndStoresOnlyItsSha256Hash() {
    IssuedToken issued = issue();
    assertThat(issued.value()).startsWith("tk_");
    assertThat(issued.token().sid()).matches("^s_[a-z0-9]{16}$");
    assertThat(
            owner
                .sql("SELECT count(*) FROM uam_opaque_token WHERE sid = ?")
                .param(issued.token().sid())
                .query(Long.class)
                .single())
        .isEqualTo(1);
    // the plain value is nowhere in the table
    assertThat(
            owner
                .sql("SELECT encode(token_hash, 'escape') || sid FROM uam_opaque_token")
                .query(String.class)
                .single())
        .doesNotContain(issued.value());
    assertThat(
            owner
                .sql("SELECT length(token_hash) FROM uam_opaque_token")
                .query(Integer.class)
                .single())
        .isEqualTo(32);
  }

  @Test
  void authenticatesAValidToken() {
    IssuedToken issued = issue();
    Optional<OpaqueToken> found = tokens.authenticate(issued.value());
    assertThat(found).isPresent();
    assertThat(found.get().sid()).isEqualTo(issued.token().sid());
  }

  @Test
  void unknownOrMalformedTokensAreRejected() {
    issue();
    assertThat(tokens.authenticate("tk_doesnotexist")).isEmpty();
    assertThat(tokens.authenticate("")).isEmpty();
    assertThat(tokens.authenticate(null)).isEmpty();
  }

  @Test
  void idleTimeoutIs30MinutesAndIsExtendedByUse() {
    IssuedToken issued = issue();
    clock.advance(Duration.ofMinutes(29));
    assertThat(tokens.authenticate(issued.value())).isPresent(); // slides the window
    clock.advance(Duration.ofMinutes(29));
    assertThat(tokens.authenticate(issued.value())).isPresent();
    clock.advance(Duration.ofMinutes(31));
    assertThat(tokens.authenticate(issued.value())).isEmpty();
  }

  @Test
  void absoluteLifetimeIs8HoursEvenWhenUsedContinuously() {
    IssuedToken issued = issue();
    for (int i = 0; i < 15; i++) {
      clock.advance(Duration.ofMinutes(29));
      tokens.authenticate(issued.value());
    }
    // 15 * 29 = 435 minutes (< 480): still valid
    assertThat(tokens.authenticate(issued.value())).isPresent();
    for (int i = 0; i < 3; i++) {
      clock.advance(Duration.ofMinutes(29));
      tokens.authenticate(issued.value());
    }
    // 18 * 29 = 522 minutes (> 480): beyond the absolute limit
    assertThat(tokens.authenticate(issued.value())).isEmpty();
  }

  @Test
  void revokedTokensStopWorking() {
    IssuedToken issued = issue();
    tokens.revoke(issued.value());
    assertThat(tokens.authenticate(issued.value())).isEmpty();
    tokens.revoke(issued.value()); // idempotent
    tokens.revoke("tk_unknown"); // unknown is a no-op
  }

  @Test
  void issuedTokensAreUnique() {
    IssuedToken first = issue();
    IssuedToken second =
        tokens.issue(first.token().accountId(), first.token().userId(), first.token().tenantId());
    assertThat(second.value()).isNotEqualTo(first.value());
    assertThat(second.token().sid()).isNotEqualTo(first.token().sid());
  }
}
