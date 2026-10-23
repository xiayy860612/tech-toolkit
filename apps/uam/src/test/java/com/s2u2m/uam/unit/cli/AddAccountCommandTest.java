package com.s2u2m.uam.unit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.application.AccountProvisioningService.Provisioned;
import com.s2u2m.uam.cli.AddAccountCommand;
import com.s2u2m.uam.domain.policy.PasswordPolicy;
import com.s2u2m.uam.support.Fakes.FakeAccounts;
import com.s2u2m.uam.support.Fakes.FakeClock;
import com.s2u2m.uam.support.Fakes.FakeCredentials;
import com.s2u2m.uam.support.Fakes.FakePasswordHasher;
import com.s2u2m.uam.support.Fakes.FakeTransactionRunner;
import com.s2u2m.uam.support.Fakes.FakeUsers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T052：{@code add-account} 给已有用户追加「账号名 / 密码」账号，账号名重复被拒，密码只经标准输入。 */
class AddAccountCommandTest {

  private static final String PASSWORD = "Passw0rd-fake-1";

  private final FakeUsers users = new FakeUsers();
  private final FakeAccounts accounts = new FakeAccounts();
  private final FakeCredentials credentials = new FakeCredentials();
  private final AccountProvisioningService service =
      new AccountProvisioningService(
          users,
          accounts,
          credentials,
          new FakePasswordHasher(),
          new PasswordPolicy(10),
          new FakeTransactionRunner(users, accounts, credentials),
          new FakeClock(Instant.parse("2026-10-24T08:00:00Z")));
  private final AddAccountCommand command = new AddAccountCommand(service);

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private Provisioned existing;

  @BeforeEach
  void setUp() {
    existing = service.createUserWithAccount("系统管理员", "Admin", PASSWORD);
  }

  private int run(String stdin, String... args) {
    return command.run(
        List.of(args),
        new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private String out() {
    return out.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return err.toString(StandardCharsets.UTF_8);
  }

  @Test
  void hasTheDocumentedName() {
    assertThat(command.name()).isEqualTo("add-account");
  }

  @Test
  void addsASecondAccountByUserId() {
    int code =
        run(PASSWORD + "\n", "--user-id", existing.userId().toString(), "--account-name", "second");

    assertThat(code).isZero();
    assertThat(accounts.byId.values())
        .extracting(a -> a.userId(), a -> a.loginName().raw())
        .containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple(existing.userId(), "Admin"),
            org.assertj.core.api.Assertions.tuple(existing.userId(), "second"));
    assertThat(users.byId).hasSize(1);
    assertThat(out().strip()).isNotEmpty();
  }

  @Test
  void addsASecondAccountByAnExistingAccountName() {
    int code = run(PASSWORD + "\n", "--account-name-of", "admin", "--account-name", "second");

    assertThat(code).isZero();
    assertThat(accounts.byId.values()).extracting(a -> a.userId()).containsOnly(existing.userId());
    assertThat(accounts.byId).hasSize(2);
  }

  @Test
  void exactlyOneOfUserIdAndAccountNameOfIsRequired() {
    assertThat(run(PASSWORD + "\n", "--account-name", "second")).isEqualTo(2);
    assertThat(
            run(
                PASSWORD + "\n",
                "--user-id",
                existing.userId().toString(),
                "--account-name-of",
                "admin",
                "--account-name",
                "second"))
        .isEqualTo(2);
    assertThat(accounts.byId).hasSize(1);
  }

  @Test
  void missingAccountNameOrMalformedUserIdIsAUsageError() {
    assertThat(run(PASSWORD + "\n", "--user-id", existing.userId().toString())).isEqualTo(2);
    assertThat(run(PASSWORD + "\n", "--user-id", "not-a-uuid", "--account-name", "second"))
        .isEqualTo(2);
    assertThat(accounts.byId).hasSize(1);
  }

  @Test
  void duplicateAccountNameIsRejectedCaseInsensitively() {
    int code =
        run(PASSWORD + "\n", "--user-id", existing.userId().toString(), "--account-name", "ADMIN");

    assertThat(code).isEqualTo(1);
    assertThat(accounts.byId).hasSize(1);
    assertThat(err()).contains("account_name_taken");
  }

  @Test
  void unknownUserIsRejected() {
    int code =
        run(PASSWORD + "\n", "--user-id", UUID.randomUUID().toString(), "--account-name", "second");

    assertThat(code).isEqualTo(1);
    assertThat(err()).contains("user_not_found");
    assertThat(accounts.byId).hasSize(1);
  }

  @Test
  void unknownAccountNameOfIsRejected() {
    int code = run(PASSWORD + "\n", "--account-name-of", "nobody", "--account-name", "second");

    assertThat(code).isEqualTo(1);
    assertThat(err()).contains("user_not_found");
  }

  @Test
  void passwordComesOnlyFromStdin() {
    assertThat(run("", "--user-id", existing.userId().toString(), "--account-name", "second"))
        .isEqualTo(2);
    int code =
        run(
            PASSWORD + "\n",
            "--user-id",
            existing.userId().toString(),
            "--account-name",
            "second",
            "--password",
            "Leak-1234567");

    assertThat(code).isEqualTo(2);
    assertThat(err() + out()).doesNotContain("Leak-1234567");
    assertThat(accounts.byId).hasSize(1);
  }

  @Test
  void weakPasswordAndBadNameAreRejectedWithoutEchoingTheValues() {
    int code = run("weak\n", "--user-id", existing.userId().toString(), "--account-name", "x y");

    assertThat(code).isEqualTo(1);
    assertThat(err()).contains("password").contains("accountName").doesNotContain("weak\n");
    assertThat(accounts.byId).hasSize(1);
  }

  @Test
  void neverPrintsThePasswordOrItsHash() {
    run(PASSWORD + "\n", "--user-id", existing.userId().toString(), "--account-name", "second");

    assertThat(out() + err()).doesNotContain(PASSWORD).doesNotContain("fake$");
  }
}
