package tech.toolkit.tenantcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.identity.Role;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;
import tech.toolkit.tenantcore.support.TestAccounts;

class BootstrapRunnerIT extends AbstractPostgresIT {

  @Autowired BootstrapRunner runner;
  @Autowired BootstrapService service;
  @Autowired TransactionTemplate tx;

  @MockitoBean(name = "processExit")
  IntConsumer exit;

  private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

  @Test
  void createsTheFirstAdminInTheDefaultTenantWithAOneTimeTemporaryPassword() {
    String username = TestAccounts.uniqueName();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    root.addAppender(logs);
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true));
    try {
      runner.run(new DefaultApplicationArguments("--bootstrap", "--username=" + username));
    } finally {
      System.setOut(original);
      root.detachAppender(logs);
    }

    String out = captured.toString();
    assertThat(out).contains(username).contains("Temporary password");
    String marker = "changed at first login): ";
    String password =
        out.substring(out.indexOf(marker) + marker.length()).lines().findFirst().orElse("").trim();
    assertThat(password).hasSize(16);
    // 密码不进入日志
    assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(password));

    assertThat(
            owner.queryForObject(
                "select role from app_user where username_normalized=? and tenant_id=0",
                String.class,
                username))
        .isEqualTo(Role.TENANT_ADMIN.name());
    assertThat(
            owner.queryForObject(
                "select must_change_password from app_user where username_normalized=?",
                Boolean.class,
                username))
        .isTrue();
    assertThat(
            owner.queryForObject(
                "select count(*) from information_schema.tables where table_name='tenant'",
                Integer.class))
        .isZero(); // 不创建任何租户实体
  }

  @Test
  void displayNameDefaultsToTheUsernameAndDuplicateUsernamesAreRejected() {
    String username = TestAccounts.uniqueName();
    service.createFirstAdmin(username, null, null);
    assertThat(
            owner.queryForObject(
                "select display_name from app_user where username_normalized=?",
                String.class,
                username))
        .isEqualTo(username);
    assertThatThrownBy(() -> service.createFirstAdmin(username, null, null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.USERNAME_TAKEN));
  }
}
