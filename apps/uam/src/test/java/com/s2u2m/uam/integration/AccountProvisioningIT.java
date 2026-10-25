package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.application.AccountProvisioningService.Provisioned;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Credential;
import com.s2u2m.uam.domain.policy.PasswordPolicy;
import com.s2u2m.uam.domain.port.AccountRepository;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.CredentialRepository;
import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.domain.port.TransactionRunner;
import com.s2u2m.uam.domain.port.UserRepository;
import com.s2u2m.uam.domain.port.UserStatusLock;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T052 / T064：真实 PostgreSQL 上验证「单事务、不留半成品」与账号名唯一性（含并发）。 */
class AccountProvisioningIT extends IntegrationTestBase {

  private static final String PASSWORD = "Passw0rd-fake-1";

  @Autowired UserRepository users;
  @Autowired AccountRepository accounts;
  @Autowired CredentialRepository credentials;
  @Autowired PasswordHasher hasher;
  @Autowired TransactionRunner tx;
  @Autowired UserStatusLock statusLock;
  @Autowired Clock clock;
  @Autowired AccountProvisioningService service;

  private long rows(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  @Test
  void createsUserAccountAndCredentialTogether() {
    Provisioned created = service.createUserWithAccount("张三", "ZhangSan", PASSWORD);

    assertThat(rows("app_user")).isEqualTo(1);
    assertThat(rows("account")).isEqualTo(1);
    assertThat(rows("credential")).isEqualTo(1);
    String hash = credentials.findPasswordHash(created.accountId()).orElseThrow();
    assertThat(hash).startsWith("{argon2@SpringSecurity_v5_8}$argon2id$");
    assertThat(hasher.matches(PASSWORD, hash)).isTrue();
  }

  @Test
  void aFailureAfterTheUserAndAccountInsertsRollsEverythingBack() {
    CredentialRepository failing =
        new CredentialRepository() {
          @Override
          public Optional<String> findPasswordHash(UUID accountId) {
            return credentials.findPasswordHash(accountId);
          }

          @Override
          public void insert(Credential credential) {
            throw new IllegalStateException("模拟凭据写入失败");
          }

          @Override
          public void updatePasswordHash(UUID accountId, String passwordHash) {
            credentials.updatePasswordHash(accountId, passwordHash);
          }

          @Override
          public boolean upgradePasswordHash(
              UUID accountId, String expectedHash, String passwordHash) {
            return credentials.upgradePasswordHash(accountId, expectedHash, passwordHash);
          }
        };
    AccountProvisioningService broken =
        new AccountProvisioningService(
            users, accounts, failing, hasher, new PasswordPolicy(10), tx, statusLock, clock);

    assertThatThrownBy(() -> broken.createUserWithAccount("张三", "zhangsan", PASSWORD))
        .isInstanceOf(IllegalStateException.class);

    assertThat(rows("app_user")).isZero();
    assertThat(rows("account")).isZero();
    assertThat(rows("credential")).isZero();
  }

  @Test
  void addAccountRollsBackTheAccountWhenTheCredentialFails() {
    Provisioned first = service.createUserWithAccount("张三", "zhangsan", PASSWORD);
    CredentialRepository failing =
        new CredentialRepository() {
          @Override
          public Optional<String> findPasswordHash(UUID accountId) {
            return Optional.empty();
          }

          @Override
          public void insert(Credential credential) {
            throw new IllegalStateException("模拟凭据写入失败");
          }

          @Override
          public void updatePasswordHash(UUID accountId, String passwordHash) {}

          @Override
          public boolean upgradePasswordHash(
              UUID accountId, String expectedHash, String passwordHash) {
            return false;
          }
        };
    AccountProvisioningService broken =
        new AccountProvisioningService(
            users, accounts, failing, hasher, new PasswordPolicy(10), tx, statusLock, clock);

    assertThatThrownBy(() -> broken.addAccount(first.userId(), "zhangsan.2", PASSWORD))
        .isInstanceOf(IllegalStateException.class);

    assertThat(rows("account")).isEqualTo(1);
  }

  @Test
  void duplicateNameIgnoringCaseIsAccountNameTakenAndLeavesNoHalfUser() {
    service.createUserWithAccount("张三", "ZhangSan", PASSWORD);

    assertThatThrownBy(() -> service.createUserWithAccount("李四", "zhangsan", PASSWORD))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NAME_TAKEN));

    assertThat(rows("app_user")).isEqualTo(1);
    assertThat(rows("account")).isEqualTo(1);
    assertThat(rows("credential")).isEqualTo(1);
  }

  @Test
  void concurrentCreationOfTheSameAccountNameHasExactlyOneWinner() throws Exception {
    int attempts = 8;
    CountDownLatch gate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      int n = i;
      results.add(
          pool.submit(
              () -> {
                gate.await();
                try {
                  service.createUserWithAccount("用户" + n, "Same.Name", PASSWORD);
                  return true;
                } catch (DomainException e) {
                  assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NAME_TAKEN);
                  return false;
                }
              }));
    }
    gate.countDown();
    long winners = 0;
    for (Future<Boolean> result : results) {
      if (result.get()) {
        winners++;
      }
    }
    pool.shutdown();

    assertThat(winners).isEqualTo(1);
    assertThat(rows("app_user")).as("失败的并发请求不留半成品用户").isEqualTo(1);
    assertThat(rows("account")).isEqualTo(1);
    assertThat(rows("credential")).isEqualTo(1);
  }

  /** 每次 {@code count()} 读到结果之后再睡一会儿：把「读到 0 → 插入」之间的窗口撑开，让并发的 init-admin 必然交错。 */
  private UserRepository slowCount(long millis) {
    return (UserRepository)
        Proxy.newProxyInstance(
            UserRepository.class.getClassLoader(),
            new Class<?>[] {UserRepository.class},
            (proxy, method, args) -> {
              try {
                Object result = method.invoke(users, args);
                if (method.getName().equals("count")) {
                  Thread.sleep(millis);
                }
                return result;
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  @Test
  void concurrentInitialUsersCreateExactlyOneUser() throws Exception {
    AccountProvisioningService racing =
        new AccountProvisioningService(
            slowCount(300),
            accounts,
            credentials,
            hasher,
            new PasswordPolicy(10),
            tx,
            statusLock,
            clock);
    CountDownLatch gate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<Optional<Provisioned>>> results = new ArrayList<>();
    for (String name : List.of("admin-a", "admin-b")) {
      results.add(
          pool.submit(
              () -> {
                gate.await();
                return racing.createInitialUserWithAccount("系统管理员", name, PASSWORD);
              }));
    }
    gate.countDown();
    long created = 0;
    for (Future<Optional<Provisioned>> result : results) {
      if (result.get().isPresent()) {
        created++;
      }
    }
    pool.shutdown();

    assertThat(created).isEqualTo(1);
    assertThat(rows("app_user")).isEqualTo(1);
    assertThat(rows("account")).isEqualTo(1);
    assertThat(rows("credential")).isEqualTo(1);
  }

  @Test
  void initialUserOnlyWhenThereAreNoUsers() {
    assertThat(service.createInitialUserWithAccount("系统管理员", "admin", PASSWORD)).isPresent();
    assertThat(service.createInitialUserWithAccount("另一个", "other", PASSWORD)).isEmpty();

    assertThat(rows("app_user")).isEqualTo(1);
  }
}
