package com.s2u2m.uam.unit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.cli.InitAdminCommand;
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
import org.junit.jupiter.api.Test;

/** T052：{@code init-admin} 只在没有任何用户时创建首个用户与账号，幂等，密码只经标准输入。 */
class InitAdminCommandTest {

  private static final String PASSWORD = "Passw0rd-fake-1";

  private final FakeUsers users = new FakeUsers();
  private final FakeAccounts accounts = new FakeAccounts();
  private final FakeCredentials credentials = new FakeCredentials();
  private final InitAdminCommand command =
      new InitAdminCommand(
          new AccountProvisioningService(
              users,
              accounts,
              credentials,
              new FakePasswordHasher(),
              new PasswordPolicy(10),
              new FakeTransactionRunner(users, accounts, credentials),
              () -> {},
              new FakeClock(Instant.parse("2026-10-24T08:00:00Z"))));

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

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
    assertThat(command.name()).isEqualTo("init-admin");
  }

  @Test
  void createsTheFirstUserWithAPasswordAccountFromStdin() {
    int code = run(PASSWORD + "\n", "--account-name", "admin", "--user-name", "系统管理员");

    assertThat(code).isZero();
    assertThat(users.byId.values())
        .singleElement()
        .satisfies(u -> assertThat(u.name()).isEqualTo("系统管理员"));
    assertThat(accounts.byId.values())
        .singleElement()
        .satisfies(a -> assertThat(a.loginName().raw()).isEqualTo("admin"));
    assertThat(credentials.hashes).hasSize(1);
    // 标准输出只给结果（用户标识），人读信息走标准错误
    assertThat(out().strip()).isEqualTo(users.byId.keySet().iterator().next().toString());
    assertThat(err()).contains("admin");
  }

  @Test
  void acceptsEqualsSignOptionsAndStripsTheLineEnding() {
    int code = run(PASSWORD + "\r\n", "--account-name=admin", "--user-name=系统管理员");

    assertThat(code).isZero();
    assertThat(credentials.hashes.values()).containsExactly("fake$" + PASSWORD);
  }

  @Test
  void isIdempotentWhenAUserAlreadyExists() {
    run(PASSWORD + "\n", "--account-name", "admin", "--user-name", "系统管理员");
    out.reset();
    err.reset();

    int code = run(PASSWORD + "\n", "--account-name", "other", "--user-name", "另一个");

    assertThat(code).isZero();
    assertThat(users.byId).hasSize(1);
    assertThat(accounts.byId).hasSize(1);
    assertThat(out()).isEmpty();
    assertThat(err()).contains("已存在用户");
  }

  @Test
  void missingStdinPasswordIsAUsageError() {
    int code = run("", "--account-name", "admin", "--user-name", "系统管理员");

    assertThat(code).isEqualTo(2);
    assertThat(users.byId).isEmpty();
    assertThat(err()).contains("标准输入");
  }

  @Test
  void aPasswordOptionIsRejectedAndNeverEchoed() {
    int code =
        run(
            PASSWORD + "\n",
            "--account-name",
            "admin",
            "--user-name",
            "x",
            "--password",
            "Leak-1234567");

    assertThat(code).isEqualTo(2);
    assertThat(users.byId).isEmpty();
    assertThat(err()).contains("标准输入").doesNotContain("Leak-1234567");
    assertThat(out()).doesNotContain("Leak-1234567");
  }

  @Test
  void missingRequiredOptionsAreUsageErrors() {
    assertThat(run(PASSWORD + "\n", "--user-name", "系统管理员")).isEqualTo(2);
    assertThat(run(PASSWORD + "\n", "--account-name", "admin")).isEqualTo(2);
    assertThat(run(PASSWORD + "\n", "--account-name")).isEqualTo(2);
    assertThat(users.byId).isEmpty();
  }

  @Test
  void unknownOptionsAndStrayArgumentsAreUsageErrors() {
    assertThat(run(PASSWORD + "\n", "--account-name", "admin", "--user-name", "x", "--bogus", "1"))
        .isEqualTo(2);
    assertThat(run(PASSWORD + "\n", "--account-name", "admin", "--user-name", "x", "stray"))
        .isEqualTo(2);
  }

  @Test
  void aStrayArgumentIsNeverEchoedSoAMistypedPasswordCannotReachTheLogs() {
    int code = run(PASSWORD + "\n", "--account-name", "admin", "--user-name", "x", "Leak-1234567");

    assertThat(code).isEqualTo(2);
    assertThat(users.byId).isEmpty();
    assertThat(err()).contains("多余的参数").doesNotContain("Leak-1234567");
    assertThat(out()).doesNotContain("Leak-1234567");
  }

  @Test
  void unknownOptionsDoNotEchoTheirValues() {
    int code =
        run(
            PASSWORD + "\n",
            "--account-name",
            "admin",
            "--user-name",
            "x",
            "--bogus=Leak-1234567",
            "--other",
            "Leak-7654321");

    assertThat(code).isEqualTo(2);
    assertThat(err()).doesNotContain("Leak-1234567").doesNotContain("Leak-7654321");
  }

  @Test
  void weakPasswordIsRejectedByStrengthPolicyWithoutEchoingIt() {
    int code = run("short1\n", "--account-name", "admin", "--user-name", "系统管理员");

    assertThat(code).isEqualTo(1);
    assertThat(users.byId).isEmpty();
    assertThat(err()).contains("password").contains("too_short").doesNotContain("short1");
  }

  @Test
  void invalidAccountNameIsRejectedByNameRules() {
    int code = run(PASSWORD + "\n", "--account-name", "a b", "--user-name", "系统管理员");

    assertThat(code).isEqualTo(1);
    assertThat(users.byId).isEmpty();
    assertThat(err()).contains("accountName").contains("invalid_chars");
  }

  @Test
  void neverPrintsThePasswordOrItsHash() {
    run(PASSWORD + "\n", "--account-name", "admin", "--user-name", "系统管理员");

    assertThat(out() + err()).doesNotContain(PASSWORD).doesNotContain("fake$");
  }
}
