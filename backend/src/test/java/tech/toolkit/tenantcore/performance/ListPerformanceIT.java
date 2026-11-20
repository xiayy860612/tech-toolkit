package tech.toolkit.tenantcore.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;
import tech.toolkit.tenantcore.support.TestSessions;

/** SC-005：租户内 1,000 用户、100 并发会话下，用户列表与搜索 p95 ≤ 2 秒。默认不在 CI 主流程运行。 */
@Tag("performance")
@AutoConfigureMockMvc
class ListPerformanceIT extends AbstractPostgresIT {

  private static final long TENANT = 900;
  private static final int USERS = 1_000;
  private static final int CONCURRENCY = 100;
  private static final int REQUESTS_PER_SESSION = 5;

  @Autowired MockMvc mvc;
  @Autowired TestAccounts fixture;
  @Autowired TestSessions sessions;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

  @Test
  void listAndSearchStayWithinTwoSecondsAtP95() throws Exception {
    var admin =
        fixture.create(
            TENANT, TestAccounts.uniqueName(), "Str0ng-Passw0rd", Role.TENANT_ADMIN, false);
    seedUsers();
    List<TestSessions.Open> open = new ArrayList<>();
    for (int i = 0; i < CONCURRENCY; i++) {
      open.add(sessions.open(TENANT, admin));
    }

    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
    List<Future<List<Long>>> futures = new ArrayList<>();
    for (TestSessions.Open s : open) {
      Callable<List<Long>> job =
          () -> {
            List<Long> millis = new ArrayList<>();
            for (int i = 0; i < REQUESTS_PER_SESSION; i++) {
              long t0 = System.nanoTime();
              var request =
                  i % 2 == 0
                      ? get("/users").param("size", "50")
                      : get("/users").param("keyword", "perf1");
              mvc.perform(request.header("Authorization", s.bearer())).andExpect(status().isOk());
              millis.add((System.nanoTime() - t0) / 1_000_000);
            }
            return millis;
          };
      futures.add(pool.submit(job));
    }
    List<Long> all = new ArrayList<>();
    for (Future<List<Long>> f : futures) {
      all.addAll(f.get());
    }
    pool.shutdown();
    Collections.sort(all);
    long p95 = all.get((int) Math.ceil(all.size() * 0.95) - 1);
    System.out.printf("SC-005 list/search p95 = %d ms over %d requests%n", p95, all.size());
    assertThat(p95).isLessThanOrEqualTo(2_000);
  }

  /** 直接用 SQL 批量造数（绕开 Argon2），凭证哈希对列表无影响。 */
  private void seedUsers() {
    owner.update(
        "insert into app_user(tenant_id, display_name, role, username, username_normalized,"
            + " password_hash, must_change_password)"
            + " select ?, 'perf user ' || g, 'MEMBER', 'perf' || g, 'perf' || g, 'h', false"
            + " from generate_series(1, ?) g",
        TENANT,
        USERS);
  }
}
