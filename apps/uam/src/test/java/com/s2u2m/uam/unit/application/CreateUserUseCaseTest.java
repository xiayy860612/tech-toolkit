package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.application.CreateUserCommand;
import com.s2u2m.uam.application.CreateUserUseCase;
import com.s2u2m.uam.application.GetUserUseCase;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.OperatorContext;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.policy.PasswordPolicy;
import com.s2u2m.uam.support.Fakes.FakeAccounts;
import com.s2u2m.uam.support.Fakes.FakeClock;
import com.s2u2m.uam.support.Fakes.FakeCredentials;
import com.s2u2m.uam.support.Fakes.FakeIdempotencyStore;
import com.s2u2m.uam.support.Fakes.FakePasswordHasher;
import com.s2u2m.uam.support.Fakes.FakeTransactionRunner;
import com.s2u2m.uam.support.Fakes.FakeUsers;
import com.s2u2m.uam.support.Fakes.RecordingWriteLog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/** T096：新建用户用例（假端口）——只负责编排：幂等键、写日志、委托 {@code AccountProvisioningService}。 */
class CreateUserUseCaseTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
  private static final String PASSWORD = "Passw0rd-fake-1";
  private static final String CLIENT = "admin-center";
  private static final String KEY = "0123456789abcdef-key";

  private final JsonMapper json = JsonMapper.builder().build();
  private final FakeUsers users = new FakeUsers();
  private final FakeAccounts accounts = new FakeAccounts();
  private final FakeCredentials credentials = new FakeCredentials();
  private final FakePasswordHasher hasher = new FakePasswordHasher();
  private final FakeTransactionRunner tx = new FakeTransactionRunner(users, accounts, credentials);
  private final FakeIdempotencyStore store = new FakeIdempotencyStore();
  private final RecordingWriteLog writeLog = new RecordingWriteLog();
  private final OperatorContext operator =
      new OperatorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

  private AccountProvisioningService provisioning;
  private GetUserUseCase getUser;
  private CreateUserUseCase useCase;

  @BeforeEach
  void setUp() {
    provisioning =
        new AccountProvisioningService(
            users, accounts, credentials, hasher, new PasswordPolicy(10), tx, new FakeClock(T0));
    getUser = new GetUserUseCase(users, accounts);
    useCase = useCase(Duration.ofSeconds(2));
  }

  private CreateUserUseCase useCase(Duration maxWait) {
    return new CreateUserUseCase(
        provisioning, getUser, store, writeLog, json, maxWait, Duration.ofMillis(5));
  }

  private CreateUserCommand command(String key, String userName, String accountName, String pwd) {
    return new CreateUserCommand(operator, CLIENT, key, userName, accountName, pwd);
  }

  private CreateUserCommand command() {
    return command(KEY, "张三", "Zhang.San", PASSWORD);
  }

  private DomainException failure(CreateUserCommand command) {
    try {
      useCase.create(command);
    } catch (DomainException e) {
      return e;
    }
    throw new AssertionError("应抛 DomainException");
  }

  private WriteOperationEvent onlyEvent() {
    assertThat(writeLog.events).hasSize(1);
    return writeLog.events.get(0);
  }

  @Test
  void createsTheUserAndReturnsItWithItsAccount() {
    GetUserUseCase.Result result = useCase.create(command());

    assertThat(result.user().name()).isEqualTo("张三");
    assertThat(result.user().status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(result.accounts()).hasSize(1);
    Account account = result.accounts().get(0);
    assertThat(account.loginName().raw()).isEqualTo("Zhang.San");
    assertThat(account.kind()).isEqualTo(AccountKind.PASSWORD);
    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(users.byId).hasSize(1);
    assertThat(credentials.hashes).hasSize(1);
  }

  @Test
  void writesASuccessEventWithOperatorClientAndTarget() {
    GetUserUseCase.Result result = useCase.create(command());

    WriteOperationEvent event = onlyEvent();
    assertThat(event.action()).isEqualTo(Action.USER_CREATE);
    assertThat(event.outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(event.operator()).isEqualTo(operator);
    assertThat(event.clientId()).isEqualTo(CLIENT);
    assertThat(event.targetType()).isEqualTo("user");
    assertThat(event.targetId()).isEqualTo(result.user().id().toString());
    assertThat(event.reason()).isNull();
  }

  @Test
  void storesTheResponseSnapshotButNeverThePasswordOrItsHash() {
    useCase.create(command());

    assertThat(store.completed).hasSize(1);
    String snapshot = store.snapshotOf(CLIENT, KEY);
    assertThat(snapshot).contains("Zhang.San").doesNotContain(PASSWORD).doesNotContain("fake$");
    assertThat(writeLog.events.toString()).doesNotContain(PASSWORD).doesNotContain("fake$");
  }

  @Test
  void replayingTheSameKeyReturnsTheOriginalResultWithoutCreatingAgain() {
    GetUserUseCase.Result first = useCase.create(command());
    int hashes = hasher.hashCalls;

    GetUserUseCase.Result replay = useCase.create(command());

    assertThat(replay).isEqualTo(first);
    assertThat(users.byId).hasSize(1);
    assertThat(hasher.hashCalls).as("重放不再哈希").isEqualTo(hashes);
    assertThat(writeLog.events).as("重放不是新的写操作，不重复记日志").hasSize(1);
  }

  @Test
  void aReplayWithADifferentPasswordOnlyStillReturnsTheOriginalResult() {
    GetUserUseCase.Result first = useCase.create(command());

    GetUserUseCase.Result replay =
        useCase.create(command(KEY, "张三", "zhang.san", "Other-Passw0rd-2"));

    assertThat(replay).isEqualTo(first);
    assertThat(users.byId).hasSize(1);
  }

  @Test
  void theSameKeyWithADifferentRequestIsRejectedAndKeepsTheOriginalResult() {
    GetUserUseCase.Result first = useCase.create(command());

    DomainException error = failure(command(KEY, "李四", "lisi.account", PASSWORD));

    assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(error.fieldErrors()).containsExactly(new FieldError("Idempotency-Key", "reused"));
    assertThat(users.byId).hasSize(1);
    assertThat(writeLog.events).hasSize(2);
    assertThat(writeLog.events.get(1).outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(useCase.create(command())).isEqualTo(first);
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      value = {
        "NULL, required",
        "'', required",
        "'   ', required",
        "0123456789abcde, too_short",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0, too_long",
        "0123456789abcdef-中文-key, invalid_chars",
        "0123456789 abcdef-key, invalid_chars"
      })
  void anInvalidIdempotencyKeyIsRejectedBeforeAnythingIsCreated(String key, String reason) {
    DomainException error = failure(command(key, "张三", "zhangsan", PASSWORD));

    assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(error.fieldErrors()).containsExactly(new FieldError("Idempotency-Key", reason));
    assertThat(users.byId).isEmpty();
    assertThat(store.claims).isZero();
    assertThat(onlyEvent().outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(onlyEvent().reason()).isEqualTo("validation_failed");
  }

  @Test
  void fieldErrorsFromTheServiceArePassedThroughUnchangedAndLoggedAsRejected() {
    DomainException error = failure(command(KEY, "  ", "a!", "short"));

    assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(error.fieldErrors())
        .containsExactly(
            new FieldError("userName", "required"),
            new FieldError("accountName", "invalid_chars"),
            new FieldError("password", "too_short"));
    WriteOperationEvent event = onlyEvent();
    assertThat(event.action()).isEqualTo(Action.USER_CREATE);
    assertThat(event.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(event.reason()).isEqualTo("validation_failed");
    assertThat(event.targetId()).isNull();
    assertThat(users.byId).isEmpty();
  }

  @Test
  void aRejectedAttemptDoesNotBurnTheKeySoACorrectedRetryWithTheSameKeySucceeds() {
    failure(command(KEY, "张三", "zhangsan", "short"));
    assertThat(store.released).hasSize(1);
    assertThat(store.holds(CLIENT, KEY)).isFalse();

    GetUserUseCase.Result result = useCase.create(command(KEY, "张三", "zhangsan", PASSWORD));

    assertThat(result.user().name()).isEqualTo("张三");
    assertThat(users.byId).hasSize(1);
    assertThat(writeLog.events)
        .extracting(WriteOperationEvent::outcome)
        .containsExactly(Outcome.REJECTED, Outcome.SUCCESS);
  }

  @Test
  void aTakenAccountNameIsRejectedCaseInsensitivelyAndLogged() {
    User ghost = new User(UUID.randomUUID(), "已停用", UserStatus.DISABLED, T0);
    users.insert(ghost);
    accounts.insert(
        new Account(
            UUID.randomUUID(),
            ghost.id(),
            AccountKind.PASSWORD,
            LoginName.of("Ghost.Acct"),
            AccountStatus.DISABLED,
            T0));

    DomainException error = failure(command(KEY, "新人", "gHOST.aCCT", PASSWORD));

    assertThat(error.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NAME_TAKEN);
    assertThat(users.byId).as("不留半成品").hasSize(1);
    WriteOperationEvent event = onlyEvent();
    assertThat(event.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(event.reason()).isEqualTo("account_name_taken");
    assertThat(store.released).hasSize(1);
  }

  @Test
  void anUnexpectedFailureIsLoggedAsFailedReleasesTheKeyAndLeavesNothingBehind() {
    credentials.failOnInsert = new IllegalStateException("模拟凭据写入失败");

    assertThatThrownBy(() -> useCase.create(command())).isInstanceOf(IllegalStateException.class);

    WriteOperationEvent event = onlyEvent();
    assertThat(event.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(event.reason()).isEqualTo("internal_error");
    assertThat(store.holds(CLIENT, KEY)).isFalse();
    assertThat(users.byId).isEmpty();
    assertThat(accounts.byId).isEmpty();
  }

  @Test
  void aDuplicateWhileTheFirstIsInFlightWaitsAndReturnsTheFirstResult() {
    String fingerprint = CreateUserUseCase.fingerprint("张三", "Zhang.San");
    store.claim(CLIENT, KEY, fingerprint); // 另一个请求已占位、尚未完成
    GetUserUseCase.Result[] original = new GetUserUseCase.Result[1];
    store.onInFlight =
        count -> {
          if (count == 3) { // 占位方在等待期间完成（用另一个存储模拟它自己的执行，取其快照）
            FakeIdempotencyStore ownerStore = new FakeIdempotencyStore();
            CreateUserUseCase owner =
                new CreateUserUseCase(provisioning, getUser, ownerStore, writeLog, json);
            original[0] = owner.create(command());
            store.complete(CLIENT, KEY, fingerprint, ownerStore.snapshotOf(CLIENT, KEY));
          }
        };

    GetUserUseCase.Result result = useCase.create(command());

    assertThat(result).isEqualTo(original[0]);
    assertThat(users.byId).hasSize(1);
    assertThat(store.inFlightReturned).isEqualTo(3);
    assertThat(writeLog.events).as("等待方没有写入，只有占位方记了日志").hasSize(1);
  }

  @Test
  void ifTheFirstRequestFailsAndReleasesTheKeyTheWaitingDuplicateTakesOver() {
    store.claim(CLIENT, KEY, CreateUserUseCase.fingerprint("张三", "Zhang.San"));
    store.onInFlight = count -> store.release(CLIENT, KEY);

    GetUserUseCase.Result result = useCase.create(command());

    assertThat(result.user().name()).isEqualTo("张三");
    assertThat(users.byId).hasSize(1);
    assertThat(onlyEvent().outcome()).isEqualTo(Outcome.SUCCESS);
  }

  @Test
  void aDuplicateThatWaitsTooLongGetsIdempotencyInProgressAndCreatesNothing() {
    store.claim(CLIENT, KEY, CreateUserUseCase.fingerprint("张三", "Zhang.San"));
    useCase = useCase(Duration.ofMillis(40));

    DomainException error = failure(command());

    assertThat(error.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
    assertThat(users.byId).isEmpty();
    assertThat(store.released).as("不能释放别人占的位").isEmpty();
    WriteOperationEvent event = onlyEvent();
    assertThat(event.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(event.reason()).isEqualTo("idempotency_in_progress");
  }

  @Test
  void anInFlightRequestWithADifferentFingerprintIsRejectedWithoutWaiting() {
    store.claim(CLIENT, KEY, CreateUserUseCase.fingerprint("李四", "lisi.account"));

    DomainException error = failure(command());

    assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(error.fieldErrors()).containsExactly(new FieldError("Idempotency-Key", "reused"));
    assertThat(store.inFlightReturned).isEqualTo(1);
  }

  @Test
  void
      theFingerprintIgnoresCaseOfTheAccountNameAndSurroundingSpaceOfTheUserNameButNotThePassword() {
    assertThat(CreateUserUseCase.fingerprint(" 张三 ", "ZHANG.san"))
        .isEqualTo(CreateUserUseCase.fingerprint("张三", "zhang.SAN"));
    assertThat(CreateUserUseCase.fingerprint("张三", "zhang.san"))
        .isNotEqualTo(CreateUserUseCase.fingerprint("张三", "zhang.san2"));
    assertThat(CreateUserUseCase.fingerprint("张三", "zhang.san"))
        .isNotEqualTo(CreateUserUseCase.fingerprint("张三2", "zhang.san"));
    assertThat(CreateUserUseCase.fingerprint(null, null)).isNotBlank();
  }

  @Test
  void theCommandNeverPrintsThePassword() {
    assertThat(command().toString()).doesNotContain(PASSWORD).contains("Zhang.San");
    assertThat(List.of(command()).toString()).doesNotContain(PASSWORD);
  }
}
