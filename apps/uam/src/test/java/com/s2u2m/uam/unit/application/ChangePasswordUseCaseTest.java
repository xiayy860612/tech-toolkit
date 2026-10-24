package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.ChangePasswordCommand;
import com.s2u2m.uam.application.ChangePasswordUseCase;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.OperatorContext;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.policy.PasswordPolicy;
import com.s2u2m.uam.domain.port.CredentialRepository;
import com.s2u2m.uam.support.Fakes.FakeCredentials;
import com.s2u2m.uam.support.Fakes.FakePasswordChangeThrottle;
import com.s2u2m.uam.support.Fakes.FakePasswordHasher;
import com.s2u2m.uam.support.Fakes.RecordingWriteLog;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** T134：改密用例（假端口）——校验当前密码、新密码强度与「不同于当前」、只改路径里的账号、限流先于校验、写 {@code account.password.change} 日志。 */
class ChangePasswordUseCaseTest {

  private static final String CLIENT = "admin-center";
  private static final String IP = "203.0.113.7";
  private static final String CURRENT = "Passw0rd-fake-1";
  private static final String NEW = "Another-fake-2";

  private final FakeCredentials credentials = new FakeCredentials();
  private final FakePasswordHasher hasher = new FakePasswordHasher();
  private final FakePasswordChangeThrottle throttle = new FakePasswordChangeThrottle(5, 900);
  private final LoginThrottlePolicy throttlePolicy =
      new LoginThrottlePolicy(5, Duration.ofMinutes(15));
  private final RecordingWriteLog writeLog = new RecordingWriteLog();

  private ChangePasswordUseCase useCase;
  private UUID accountId;
  private UUID otherAccountId;
  private OperatorContext operator;

  @BeforeEach
  void setUp() {
    useCase =
        new ChangePasswordUseCase(
            credentials, hasher, throttle, throttlePolicy, new PasswordPolicy(10), writeLog);
    accountId = UUID.randomUUID();
    otherAccountId = UUID.randomUUID();
    credentials.hashes.put(accountId, "fake$" + CURRENT);
    credentials.hashes.put(otherAccountId, "fake$" + CURRENT);
    operator = new OperatorContext(UUID.randomUUID(), accountId, UUID.randomUUID());
  }

  private ChangePasswordCommand command(String current, String next) {
    return new ChangePasswordCommand(operator, CLIENT, accountId, IP, current, next);
  }

  private String subject() {
    return throttlePolicy.accountSubject(accountId, IP);
  }

  private WriteOperationEvent onlyEvent() {
    assertThat(writeLog.events).hasSize(1);
    return writeLog.events.get(0);
  }

  private DomainException rejection(ChangePasswordCommand command) {
    return org.junit.jupiter.api.Assertions.assertThrows(
        DomainException.class, () -> useCase.change(command));
  }

  @Test
  void successReplacesOnlyThePathAccountsHashAndLogsSuccess() {
    useCase.change(command(CURRENT, NEW));

    assertThat(credentials.hashes.get(accountId)).isEqualTo("fake$" + NEW);
    assertThat(credentials.hashes.get(otherAccountId)).isEqualTo("fake$" + CURRENT);
    WriteOperationEvent event = onlyEvent();
    assertThat(event.action()).isEqualTo(Action.ACCOUNT_PASSWORD_CHANGE);
    assertThat(event.action().value()).isEqualTo("account.password.change");
    assertThat(event.targetType()).isEqualTo("account");
    assertThat(event.targetId()).isEqualTo(accountId.toString());
    assertThat(event.outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(event.reason()).isNull();
    assertThat(event.operator()).isEqualTo(operator);
    assertThat(event.clientId()).isEqualTo(CLIENT);
  }

  @Test
  void successResetsTheThrottleForThatAccountAndSource() {
    throttle.admitChange(subject());
    throttle.admitChange(subject());

    useCase.change(command(CURRENT, NEW));

    assertThat(throttle.resets).containsExactly(subject());
    assertThat(throttle.failures).isEmpty();
  }

  @Test
  void aWrongCurrentPasswordIsRejectedCountedAndLeavesTheHashAlone() {
    DomainException e = rejection(command("Wrong-fake-0", NEW));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_INCORRECT);
    assertThat(credentials.hashes.get(accountId)).isEqualTo("fake$" + CURRENT);
    assertThat(throttle.failures).containsEntry(subject(), 1);
    WriteOperationEvent event = onlyEvent();
    assertThat(event.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(event.reason()).isEqualTo("current_password_incorrect");
    assertThat(event.targetId()).isEqualTo(accountId.toString());
  }

  @Test
  void anUnknownAccountLooksLikeAWrongCurrentPasswordAndStillDoesTheHashComparison() {
    UUID unknown = UUID.randomUUID();
    ChangePasswordCommand command =
        new ChangePasswordCommand(
            new OperatorContext(operator.userId(), unknown, operator.sessionRef()),
            CLIENT,
            unknown,
            IP,
            CURRENT,
            NEW);

    DomainException e = rejection(command);

    assertThat(e.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_INCORRECT);
    assertThat(hasher.comparedHashes).containsExactly(FakePasswordHasher.DUMMY);
    assertThat(throttle.failures).containsEntry(throttlePolicy.accountSubject(unknown, IP), 1);
    assertThat(credentials.hashes).doesNotContainKey(unknown);
  }

  @Test
  void theCurrentPasswordIsAlwaysComparedForAKnownAccount() {
    useCase.change(command(CURRENT, NEW));

    assertThat(hasher.comparedHashes).containsExactly("fake$" + CURRENT);
  }

  @Test
  void aBlockedSourceIsRefusedBeforeAnyHashComparisonEvenWithTheRightPassword() {
    for (int i = 0; i < 5; i++) {
      throttle.admitChange(subject());
    }

    DomainException e = rejection(command(CURRENT, NEW));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.THROTTLED);
    assertThat(e.retryAfterSeconds()).hasValue(900);
    assertThat(hasher.comparedHashes).isEmpty();
    assertThat(credentials.hashes.get(accountId)).isEqualTo("fake$" + CURRENT);
    assertThat(throttle.failures).containsEntry(subject(), 6);
    assertThat(onlyEvent().outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(onlyEvent().reason()).isEqualTo("throttled");
  }

  @Test
  void anotherSourceIsNotBlocked() {
    for (int i = 0; i < 5; i++) {
      throttle.admitChange(throttlePolicy.accountSubject(accountId, "198.51.100.9"));
    }

    useCase.change(command(CURRENT, NEW));

    assertThat(credentials.hashes.get(accountId)).isEqualTo("fake$" + NEW);
  }

  @ParameterizedTest
  @CsvSource({"short1,too_short", "onlyletterslong,too_weak", "12345678901,too_weak"})
  void aWeakNewPasswordIsRejectedWithTheFieldReasonAndNotCountedAsAGuess(
      String weak, String reason) {
    DomainException e = rejection(command(CURRENT, weak));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.WEAK_PASSWORD);
    assertThat(e.fieldErrors()).containsExactly(new FieldError("newPassword", reason));
    assertThat(credentials.hashes.get(accountId)).isEqualTo("fake$" + CURRENT);
    assertThat(throttle.failures).isEmpty();
    assertThat(onlyEvent().outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(onlyEvent().reason()).isEqualTo("weak_password");
  }

  @Test
  void aMissingNewPasswordIsWeakWithReasonRequired() {
    DomainException e = rejection(command(CURRENT, null));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.WEAK_PASSWORD);
    assertThat(e.fieldErrors()).containsExactly(new FieldError("newPassword", "required"));
  }

  @Test
  void aNewPasswordEqualToTheCurrentOneIsRejected() {
    DomainException e = rejection(command(CURRENT, CURRENT));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.SAME_AS_CURRENT);
    assertThat(e.fieldErrors()).containsExactly(new FieldError("newPassword", "same_as_current"));
    assertThat(credentials.hashes.get(accountId)).isEqualTo("fake$" + CURRENT);
    assertThat(hasher.hashCalls).isZero();
    assertThat(onlyEvent().reason()).isEqualTo("same_as_current");
  }

  @Test
  void theWrongCurrentPasswordWinsOverANewPasswordProblem() {
    DomainException e = rejection(command("Wrong-fake-0", "short"));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_INCORRECT);
  }

  @Test
  void anOperatorChangingAnotherAccountIsForbiddenBeforeAnythingIsChecked() {
    ChangePasswordCommand command =
        new ChangePasswordCommand(operator, CLIENT, otherAccountId, IP, CURRENT, NEW);

    DomainException e = rejection(command);

    assertThat(e.errorCode()).isEqualTo(ErrorCode.CLIENT_FORBIDDEN);
    assertThat(credentials.hashes.get(otherAccountId)).isEqualTo("fake$" + CURRENT);
    assertThat(hasher.comparedHashes).isEmpty();
    assertThat(throttle.failures).isEmpty();
    WriteOperationEvent event = onlyEvent();
    assertThat(event.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(event.reason()).isEqualTo("client_forbidden");
    assertThat(event.targetId()).isEqualTo(otherAccountId.toString());
  }

  @Test
  void anUnexpectedFailureIsLoggedAsFailedWithoutDetailsAndPropagates() {
    CredentialRepository broken =
        new CredentialRepository() {
          @Override
          public Optional<String> findPasswordHash(UUID id) {
            throw new IllegalStateException("db down");
          }

          @Override
          public void insert(com.s2u2m.uam.domain.model.Credential credential) {}

          @Override
          public void updatePasswordHash(UUID id, String passwordHash) {}

          @Override
          public boolean upgradePasswordHash(UUID id, String expectedHash, String passwordHash) {
            return false;
          }
        };
    ChangePasswordUseCase failing =
        new ChangePasswordUseCase(
            broken, hasher, throttle, throttlePolicy, new PasswordPolicy(10), writeLog);

    assertThatThrownBy(() -> failing.change(command(CURRENT, NEW)))
        .isInstanceOf(IllegalStateException.class);

    WriteOperationEvent event = onlyEvent();
    assertThat(event.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(event.reason()).isEqualTo("internal_error");
  }

  @Test
  void neitherTheCommandNorTheLogEventsCarryAPassword() {
    ChangePasswordCommand command = command(CURRENT, NEW);
    useCase.change(command);
    rejection(command("Wrong-fake-0", NEW));

    assertThat(command.toString()).doesNotContain(CURRENT).doesNotContain(NEW);
    assertThat(writeLog.events.toString())
        .doesNotContain(CURRENT)
        .doesNotContain(NEW)
        .doesNotContain("Wrong-fake-0")
        .doesNotContain("fake$");
  }
}
