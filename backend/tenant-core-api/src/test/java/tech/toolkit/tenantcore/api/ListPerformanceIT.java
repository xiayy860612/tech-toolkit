package tech.toolkit.tenantcore.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import tech.toolkit.tenantcore.tenant.Tenant;

/** SC-003 and SC-008: lists match the data exactly and stay fast with 500 Users. */
class ListPerformanceIT extends ApiTestBase {

  @Test
  void aFirstPageOf500UsersIsReturnedInUnderTwoSecondsAndMatchesTheData() {
    String sessionId = readySession("acme", "admin1");
    Tenant acme = core().tenants().findBySlug("acme").orElseThrow();
    JdbcClient owner = JdbcClient.create(ownerDataSource());
    for (int i = 0; i < 499; i++) { // plus the one User created by readySession = 500
      owner
          .sql(
              "INSERT INTO uam_user(id, tenant_user_id, tenant_id, display_name, created_at)"
                  + " VALUES (?, ?, ?, ?, now())")
          .params(UUID.randomUUID(), "tu_bulk" + String.format("%07d", i), acme.id(), "Bulk " + i)
          .update();
    }

    long start = System.nanoTime();
    Resp resp = call(HttpMethod.GET, "/v1/users?size=20", session(sessionId), null);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(resp.status()).isEqualTo(200);
    assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
    assertThat(resp.json().get("total").asLong()).isEqualTo(500);
    assertThat(resp.json().get("items")).hasSize(20);
    assertThat(count("uam_user")).isEqualTo(500);
  }
}
