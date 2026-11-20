package tech.toolkit.tenantcore.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;

class TenantContextIT extends AbstractPostgresIT {

  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  private void insertUser(long tenant) {
    jdbc.update(
        "insert into app_user(id, tenant_id, display_name, role) values (?, ?, 'n', 'MEMBER')",
        UUID.randomUUID(),
        tenant);
  }

  private int visibleUsers() {
    return tx.execute(s -> jdbc.queryForObject("select count(*) from app_user", Integer.class));
  }

  @Test
  void onlySeesRowsOfTheCurrentTenant() {
    TenantContext.runAs(10L, () -> tx.executeWithoutResult(s -> insertUser(10)));
    TenantContext.runAs(11L, () -> tx.executeWithoutResult(s -> insertUser(11)));

    assertThat(TenantContext.callAs(10L, this::visibleUsers)).isEqualTo(1);
    assertThat(TenantContext.callAs(11L, this::visibleUsers)).isEqualTo(1);
  }

  @Test
  void seesNothingWithoutContextAndCannotWriteAnotherTenantsRows() {
    TenantContext.runAs(12L, () -> tx.executeWithoutResult(s -> insertUser(12)));
    assertThat(visibleUsers()).isZero();
    assertThatThrownBy(
            () -> TenantContext.runAs(12L, () -> tx.executeWithoutResult(s -> insertUser(13))))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void contextDoesNotLeakIntoTheNextTransaction() {
    TenantContext.runAs(14L, () -> tx.executeWithoutResult(s -> insertUser(14)));
    assertThat(TenantContext.callAs(14L, this::visibleUsers)).isEqualTo(1);
    assertThat(TenantContext.callAs(15L, this::visibleUsers)).isZero();
    assertThat(TenantContext.current()).isEmpty();
  }
}
