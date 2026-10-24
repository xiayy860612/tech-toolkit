package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.Credential;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.port.AccountRepository;
import com.s2u2m.uam.domain.port.CredentialRepository;
import com.s2u2m.uam.domain.port.TransactionRunner;
import com.s2u2m.uam.domain.port.UserRepository;
import com.s2u2m.uam.domain.port.UserStatusLock;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T060：JDBC 仓储（显式 SQL）——{@code login_name_norm = lower(login_name)}，插入时显式设置 {@code updated_at}。 */
class JdbcRepositoriesIT extends IntegrationTestBase {

  @Autowired UserRepository users;
  @Autowired AccountRepository accounts;
  @Autowired CredentialRepository credentials;
  @Autowired UserStatusLock statusLock;
  @Autowired TransactionRunner transactions;

  private static Instant micros() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private User insertUser(String name) {
    User user = new User(UUID.randomUUID(), name, UserStatus.ACTIVE, micros());
    users.insert(user);
    return user;
  }

  private Account insertAccount(User owner, String loginName) {
    Account account =
        new Account(
            UUID.randomUUID(),
            owner.id(),
            AccountKind.PASSWORD,
            LoginName.of(loginName),
            AccountStatus.ACTIVE,
            micros());
    accounts.insert(account);
    return account;
  }

  @Test
  void userRoundTripsAndUpdatedAtIsSetByTheDatabaseClock() {
    User user = insertUser("张三");

    assertThat(users.findById(user.id())).contains(user);
    OffsetDateTime updatedAt =
        jdbc.queryForObject(
            "SELECT updated_at FROM app_user WHERE id = ?", OffsetDateTime.class, user.id());
    assertThat(Duration.between(updatedAt.toInstant(), Instant.now()).abs())
        .isLessThan(Duration.ofSeconds(30));
  }

  @Test
  void findByIdOfAnUnknownUserIsEmpty() {
    assertThat(users.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void countCountsAllUsers() {
    assertThat(users.count()).isZero();
    insertUser("张三");
    insertUser("张三");

    assertThat(users.count()).isEqualTo(2);
  }

  @Test
  void accountStoresRawAndLowerCasedNameAndIsFoundCaseInsensitively() {
    User owner = insertUser("张三");
    Account account = insertAccount(owner, "Zhang.San");

    assertThat(
            jdbc.queryForObject(
                "SELECT login_name FROM account WHERE id = ?", String.class, account.id()))
        .isEqualTo("Zhang.San");
    assertThat(
            jdbc.queryForObject(
                "SELECT login_name_norm FROM account WHERE id = ?", String.class, account.id()))
        .isEqualTo("zhang.san");
    assertThat(accounts.findByLoginName(LoginName.of("ZHANG.SAN"))).contains(account);
    assertThat(accounts.findByLoginName(LoginName.of("nobody"))).isEmpty();
    assertThat(accounts.existsByLoginName(LoginName.of("zhang.SAN"))).isTrue();
    assertThat(accounts.existsByLoginName(LoginName.of("nobody"))).isFalse();
  }

  @Test
  void duplicateNamesIgnoringCaseAreAccountNameTakenEvenForDisabledUsers() {
    User owner = insertUser("张三");
    insertAccount(owner, "Zhang");
    jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", owner.id());
    User other = insertUser("李四");

    assertThatThrownBy(() -> insertAccount(other, "ZHANG"))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NAME_TAKEN));
  }

  @Test
  void credentialRoundTripsAndUpdateRefreshesUpdatedAt() throws InterruptedException {
    Account account = insertAccount(insertUser("张三"), "zhangsan");
    credentials.insert(new Credential(account.id(), "{argon2}$hash-1"));
    OffsetDateTime first =
        jdbc.queryForObject(
            "SELECT updated_at FROM credential WHERE account_id = ?",
            OffsetDateTime.class,
            account.id());

    assertThat(credentials.findPasswordHash(account.id())).contains("{argon2}$hash-1");
    Thread.sleep(20);
    credentials.updatePasswordHash(account.id(), "{argon2}$hash-2");

    assertThat(credentials.findPasswordHash(account.id())).contains("{argon2}$hash-2");
    OffsetDateTime second =
        jdbc.queryForObject(
            "SELECT updated_at FROM credential WHERE account_id = ?",
            OffsetDateTime.class,
            account.id());
    assertThat(second).isAfter(first);
    assertThat(credentials.findPasswordHash(UUID.randomUUID())).isEmpty();
  }

  @Test
  void upgradePasswordHashOnlyReplacesTheExpectedHash() throws InterruptedException {
    Account account = insertAccount(insertUser("张三"), "zhangsan");
    credentials.insert(new Credential(account.id(), "{argon2}$hash-1"));
    OffsetDateTime first =
        jdbc.queryForObject(
            "SELECT updated_at FROM credential WHERE account_id = ?",
            OffsetDateTime.class,
            account.id());
    Thread.sleep(20);

    assertThat(credentials.upgradePasswordHash(account.id(), "{argon2}$hash-1", "{argon2}$hash-2"))
        .isTrue();
    assertThat(credentials.findPasswordHash(account.id())).contains("{argon2}$hash-2");
    assertThat(
            jdbc.queryForObject(
                "SELECT updated_at FROM credential WHERE account_id = ?",
                OffsetDateTime.class,
                account.id()))
        .isAfter(first);

    // 期间被别处改过：升级是空操作，不覆盖
    assertThat(credentials.upgradePasswordHash(account.id(), "{argon2}$hash-1", "{argon2}$hash-3"))
        .isFalse();
    assertThat(credentials.findPasswordHash(account.id())).contains("{argon2}$hash-2");
    assertThat(credentials.upgradePasswordHash(UUID.randomUUID(), "x", "y")).isFalse();
  }

  @Test
  void countActiveCountsOnlyActiveUsers() {
    insertUser("甲");
    User b = insertUser("乙");
    insertUser("丙");
    users.updateStatus(b.id(), UserStatus.DISABLED);

    assertThat(users.countActive()).isEqualTo(2);
    assertThat(users.count()).isEqualTo(3);
  }

  @Test
  void updateStatusChangesTheStatusAndMovesUpdatedAtOnlyOnARealChange()
      throws InterruptedException {
    User user = insertUser("张三");
    OffsetDateTime inserted = updatedAt(user);
    Thread.sleep(20);

    assertThat(users.updateStatus(user.id(), UserStatus.DISABLED)).isTrue();
    OffsetDateTime disabledAt = updatedAt(user);
    assertThat(users.findById(user.id()).orElseThrow().status()).isEqualTo(UserStatus.DISABLED);
    assertThat(disabledAt).isAfter(inserted);

    Thread.sleep(20);
    assertThat(users.updateStatus(user.id(), UserStatus.DISABLED)).as("已经是目标状态").isFalse();
    assertThat(updatedAt(user)).isEqualTo(disabledAt);

    Thread.sleep(20);
    assertThat(users.updateStatus(user.id(), UserStatus.ACTIVE)).isTrue();
    assertThat(updatedAt(user)).isAfter(disabledAt);
    assertThat(users.updateStatus(UUID.randomUUID(), UserStatus.DISABLED)).isFalse();
  }

  private OffsetDateTime updatedAt(User user) {
    return jdbc.queryForObject(
        "SELECT updated_at FROM app_user WHERE id = ?", OffsetDateTime.class, user.id());
  }

  @Test
  void theStatusLockSerialisesTransactionsUntilCommit() throws Exception {
    CountDownLatch holding = new CountDownLatch(1);
    AtomicLong releasedAt = new AtomicLong();
    AtomicLong secondAcquiredAt = new AtomicLong();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<?> first =
        pool.submit(
            () ->
                transactions.inTransaction(
                    () -> {
                      statusLock.lockStatusChanges();
                      holding.countDown();
                      sleep(400);
                      releasedAt.set(System.nanoTime());
                      return null;
                    }));
    holding.await();
    Future<?> second =
        pool.submit(
            () ->
                transactions.inTransaction(
                    () -> {
                      statusLock.lockStatusChanges();
                      secondAcquiredAt.set(System.nanoTime());
                      return null;
                    }));
    first.get(10, TimeUnit.SECONDS);
    second.get(10, TimeUnit.SECONDS);
    pool.shutdown();

    assertThat(secondAcquiredAt.get())
        .as("第二个事务要等第一个提交后才拿到锁")
        .isGreaterThanOrEqualTo(releasedAt.get());
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
