package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.application.AccountProvisioningService.Provisioned;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.policy.PasswordPolicy;
import com.s2u2m.uam.support.Fakes.FakeAccounts;
import com.s2u2m.uam.support.Fakes.FakeClock;
import com.s2u2m.uam.support.Fakes.FakeCredentials;
import com.s2u2m.uam.support.Fakes.FakePasswordHasher;
import com.s2u2m.uam.support.Fakes.FakeTransactionRunner;
import com.s2u2m.uam.support.Fakes.FakeUsers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T052：创建用户 / 追加账号的唯一入口（部署期命令与 US4 共用），单事务、规则校验。 */
class AccountProvisioningServiceTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
  private static final String PASSWORD = "Passw0rd-fake-1";

  private final FakeUsers users = new FakeUsers();
  private final FakeAccounts accounts = new FakeAccounts();
  private final FakeCredentials credentials = new FakeCredentials();
  private final FakePasswordHasher hasher = new FakePasswordHasher();
  private final FakeTransactionRunner tx = new FakeTransactionRunner(users, accounts, credentials);
  private final AccountProvisioningService service =
      new AccountProvisioningService(
          users, accounts, credentials, hasher, new PasswordPolicy(10), tx, new FakeClock(T0));

  private DomainException failure(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e;
    }
    throw new AssertionError("应抛 DomainException");
  }

  @Test
  void createsUserAccountAndCredentialInOneTransaction() {
    Provisioned created = service.createUserWithAccount("  张三  ", "Zhang.San", PASSWORD);

    assertThat(tx.transactions).isEqualTo(1);
    User user = users.byId.get(created.userId());
    assertThat(user.name()).isEqualTo("张三");
    assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(user.createdAt()).isEqualTo(T0);
    Account account = accounts.byId.get(created.accountId());
    assertThat(account.userId()).isEqualTo(user.id());
    assertThat(account.kind()).isEqualTo(AccountKind.PASSWORD);
    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.loginName().raw()).isEqualTo("Zhang.San");
    assertThat(account.loginName().normalized()).isEqualTo("zhang.san");
    assertThat(created.loginName()).isEqualTo("Zhang.San");
    assertThat(credentials.hashes.get(account.id())).isEqualTo("fake$" + PASSWORD);
  }

  @Test
  void aFailedCredentialInsertLeavesNoUserAndNoAccountBehind() {
    credentials.failOnInsert = new IllegalStateException("模拟凭据写入失败");

    assertThatThrownBy(() -> service.createUserWithAccount("张三", "zhangsan", PASSWORD))
        .isInstanceOf(IllegalStateException.class);

    assertThat(users.byId).isEmpty();
    assertThat(accounts.byId).isEmpty();
    assertThat(credentials.hashes).isEmpty();
  }

  @Test
  void userNameIsTrimmedAndMustBeOneToSixtyFourCharacters() {
    assertThat(service.createUserWithAccount(" ".repeat(3) + "a".repeat(64) + " ", "abc", PASSWORD))
        .isNotNull();

    DomainException blank = failure(() -> service.createUserWithAccount("   ", "def", PASSWORD));
    assertThat(blank.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(blank.fieldErrors()).containsExactly(new FieldError("userName", "required"));

    DomainException tooLong =
        failure(() -> service.createUserWithAccount("a".repeat(65), "def", PASSWORD));
    assertThat(tooLong.fieldErrors()).containsExactly(new FieldError("userName", "too_long"));

    DomainException missing = failure(() -> service.createUserWithAccount(null, "def", PASSWORD));
    assertThat(missing.fieldErrors()).containsExactly(new FieldError("userName", "required"));
  }

  @Test
  void accountNameRulesAreEnforced() {
    DomainException e = failure(() -> service.createUserWithAccount("张三", "a b", PASSWORD));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(e.fieldErrors()).containsExactly(new FieldError("accountName", "invalid_chars"));
    assertThat(users.byId).isEmpty();
  }

  @Test
  void passwordPolicyIsEnforced() {
    DomainException e = failure(() -> service.createUserWithAccount("张三", "zhangsan", "short1"));

    assertThat(e.fieldErrors()).containsExactly(new FieldError("password", "too_short"));
    assertThat(users.byId).isEmpty();
    assertThat(hasher.hashCalls).isZero();
  }

  @Test
  void allFieldErrorsAreReportedTogether() {
    DomainException e = failure(() -> service.createUserWithAccount(" ", "x", "abc"));

    assertThat(e.fieldErrors())
        .extracting(FieldError::field)
        .containsExactly("userName", "accountName", "password");
  }

  @Test
  void accountNamesAreGloballyUniqueIgnoringCase() {
    service.createUserWithAccount("张三", "Zhang.San", PASSWORD);

    DomainException e = failure(() -> service.createUserWithAccount("李四", "ZHANG.SAN", PASSWORD));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NAME_TAKEN);
    assertThat(users.byId).hasSize(1);
    assertThat(accounts.byId).hasSize(1);
  }

  @Test
  void uniquenessAlsoCoversAccountsOfDisabledUsers() {
    Provisioned created = service.createUserWithAccount("张三", "zhangsan", PASSWORD);
    users.byId.put(created.userId(), new User(created.userId(), "张三", UserStatus.DISABLED, T0));

    DomainException e = failure(() -> service.createUserWithAccount("李四", "zhangsan", PASSWORD));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NAME_TAKEN);
  }

  @Test
  void addsASecondAccountToAnExistingUser() {
    Provisioned first = service.createUserWithAccount("张三", "zhangsan", PASSWORD);

    Provisioned second = service.addAccount(first.userId(), "zhangsan.2", PASSWORD);

    assertThat(second.userId()).isEqualTo(first.userId());
    assertThat(second.accountId()).isNotEqualTo(first.accountId());
    assertThat(users.byId).hasSize(1);
    assertThat(accounts.byId.values()).extracting(a -> a.userId()).containsOnly(first.userId());
    assertThat(credentials.hashes).hasSize(2);
  }

  @Test
  void addAccountToAnUnknownUserIsUserNotFound() {
    DomainException e = failure(() -> service.addAccount(UUID.randomUUID(), "zhangsan", PASSWORD));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    assertThat(accounts.byId).isEmpty();
  }

  @Test
  void addAccountRejectsADuplicateNameAndLeavesNothingBehind() {
    Provisioned first = service.createUserWithAccount("张三", "zhangsan", PASSWORD);

    DomainException e = failure(() -> service.addAccount(first.userId(), "ZhangSan", PASSWORD));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NAME_TAKEN);
    assertThat(accounts.byId).hasSize(1);
    assertThat(credentials.hashes).hasSize(1);
  }

  @Test
  void addAccountValidatesNameAndPassword() {
    Provisioned first = service.createUserWithAccount("张三", "zhangsan", PASSWORD);

    DomainException e = failure(() -> service.addAccount(first.userId(), "x", "abc"));

    assertThat(e.fieldErrors())
        .containsExactly(
            new FieldError("accountName", "too_short"), new FieldError("password", "too_short"));
  }

  @Test
  void addsAnAccountToTheUserOfAnExistingAccountName() {
    Provisioned first = service.createUserWithAccount("张三", "ZhangSan", PASSWORD);

    Provisioned second = service.addAccountToUserOf("zhangsan", "zhangsan.2", PASSWORD);

    assertThat(second.userId()).isEqualTo(first.userId());
  }

  @Test
  void addAccountToTheUserOfAnUnknownAccountNameIsUserNotFound() {
    DomainException e = failure(() -> service.addAccountToUserOf("nobody", "zhangsan.2", PASSWORD));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
  }

  @Test
  void initialUserIsCreatedOnlyWhenThereAreNoUsers() {
    Optional<Provisioned> first = service.createInitialUserWithAccount("系统管理员", "admin", PASSWORD);
    Optional<Provisioned> second = service.createInitialUserWithAccount("另一个", "other", PASSWORD);

    assertThat(first).isPresent();
    assertThat(second).isEmpty();
    assertThat(users.byId).hasSize(1);
    assertThat(accounts.byId).hasSize(1);
  }

  @Test
  void initialUserRunsInTheSameTransactionAsTheExistenceCheck() {
    service.createInitialUserWithAccount("系统管理员", "admin", PASSWORD);

    assertThat(tx.transactions).isEqualTo(1);
  }

  @Test
  void initialUserIsStillValidatedWhenNoUserExists() {
    DomainException e =
        failure(() -> service.createInitialUserWithAccount("系统管理员", "admin", "weak"));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(users.byId).isEmpty();
  }

  @Test
  void initialUserIsANoOpWithoutValidationWhenUsersExist() {
    service.createUserWithAccount("张三", "zhangsan", PASSWORD);

    assertThat(service.createInitialUserWithAccount(" ", "x", "abc")).isEmpty();
  }

  @Test
  void fieldErrorsAreOnlyNamesAndReasonsNeverValues() {
    DomainException e =
        failure(() -> service.createUserWithAccount("张三", "zhangsan", "abcdefghijk"));

    List<FieldError> errors = e.fieldErrors();
    assertThat(errors.toString()).doesNotContain("abcdefghijk");
  }
}
