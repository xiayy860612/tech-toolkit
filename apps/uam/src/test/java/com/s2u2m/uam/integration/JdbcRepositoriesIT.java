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
import com.s2u2m.uam.domain.port.UserRepository;
import com.s2u2m.uam.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T060：JDBC 仓储（显式 SQL）——{@code login_name_norm = lower(login_name)}，插入时显式设置 {@code updated_at}。 */
class JdbcRepositoriesIT extends IntegrationTestBase {

  @Autowired UserRepository users;
  @Autowired AccountRepository accounts;
  @Autowired CredentialRepository credentials;

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
}
