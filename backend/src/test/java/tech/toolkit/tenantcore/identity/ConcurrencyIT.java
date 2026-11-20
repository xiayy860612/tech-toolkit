package tech.toolkit.tenantcore.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.toolkit.tenantcore.platform.Actor;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;

/** quickstart 场景 11、15：并发编辑与最后一名管理员守卫。 */
class ConcurrencyIT extends AbstractPostgresIT {

  private static final String PW = "Str0ng-Passw0rd";

  @Autowired UserService users;
  @Autowired TestAccounts fixture;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

  /** 同时放行所有任务，返回各自结果（成功为 null，失败为错误码）。 */
  private List<ErrorCode> raceInTenant(long tenant, List<Callable<Object>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    CountDownLatch go = new CountDownLatch(1);
    List<Future<ErrorCode>> futures = new ArrayList<>();
    for (Callable<Object> task : tasks) {
      futures.add(
          pool.submit(
              () -> {
                go.await();
                try {
                  TenantContext.callAs(
                      tenant,
                      () -> {
                        try {
                          return task.call();
                        } catch (RuntimeException e) {
                          throw e;
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                      });
                  return null;
                } catch (ApiException e) {
                  return e.code();
                }
              }));
    }
    go.countDown();
    List<ErrorCode> results = new ArrayList<>();
    for (Future<ErrorCode> f : futures) {
      results.add(f.get());
    }
    pool.shutdown();
    return results;
  }

  @Test
  void twoAdministratorsEditingTheSameUserOnlyOneWins() throws Exception {
    long tenant = 301;
    var admin = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    var target = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.MEMBER, false);
    Actor actor = new Actor(admin.user().getId(), null);
    Long id = target.user().getId();
    long version = target.user().getVersion();

    List<ErrorCode> results =
        raceInTenant(
            tenant,
            List.of(
                () ->
                    users.update(
                        actor, id, new UserService.Changes("Editor One", Role.MEMBER, version)),
                () ->
                    users.update(
                        actor, id, new UserService.Changes("Editor Two", Role.MEMBER, version))));

    assertThat(results).containsExactlyInAnyOrder(null, ErrorCode.VERSION_CONFLICT);
  }

  @Test
  void twoAdministratorsDisablingEachOtherNeverLeavesTheTenantWithoutAdministrator()
      throws Exception {
    long tenant = 302;
    var a = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    var b = fixture.create(tenant, TestAccounts.uniqueName(), PW, Role.TENANT_ADMIN, false);
    Actor actorA = new Actor(a.user().getId(), null);
    Actor actorB = new Actor(b.user().getId(), null);

    List<ErrorCode> results =
        raceInTenant(
            tenant,
            List.of(
                () -> users.disable(actorA, b.user().getId()),
                () -> users.disable(actorB, a.user().getId())));

    assertThat(results).containsExactlyInAnyOrder(null, ErrorCode.LAST_ADMIN);
    assertThat(
            owner.queryForObject(
                "select count(*) from app_user where tenant_id = ? and role='TENANT_ADMIN' and status='ACTIVE'",
                Integer.class,
                tenant))
        .isEqualTo(1);
  }
}
