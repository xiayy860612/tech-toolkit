package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.TerminateSessionCommand;
import com.s2u2m.uam.application.TerminateSessionUseCase;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.OperatorContext;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.policy.SessionPolicy;
import com.s2u2m.uam.support.Fakes.FakeSessionStore;
import com.s2u2m.uam.support.Fakes.RecordingWriteLog;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T124：强制下线用例（假端口）——目标等于操作人当前会话 → {@code cannot_terminate_own_session}（先于任何终止）；目标不存在 → {@code
 * session_not_found}；只终止目标会话；成功与被拒都写日志（{@code session.terminate}）。
 */
class TerminateSessionUseCaseTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
  private static final String CLIENT = "admin-center";
  private static final SessionPolicy POLICY =
      new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12), Duration.ofSeconds(60));

  private final FakeSessionStore sessions = new FakeSessionStore();
  private final RecordingWriteLog writeLog = new RecordingWriteLog();
  private final TerminateSessionUseCase useCase = new TerminateSessionUseCase(sessions, writeLog);

  private OperatorContext operator;

  @BeforeEach
  void setUp() {
    operator = new OperatorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  private Session newSession(String loginName) {
    User owner = new User(UUID.randomUUID(), "用户-" + loginName, UserStatus.ACTIVE, T0);
    Account account =
        new Account(
            UUID.randomUUID(),
            owner.id(),
            AccountKind.PASSWORD,
            LoginName.of(loginName),
            AccountStatus.ACTIVE,
            T0);
    Session session = Session.start(SessionRef.random(), owner, account, T0, POLICY);
    sessions.create(session, "hash-" + session.ref());
    return session;
  }

  private TerminateSessionCommand command(SessionRef target) {
    return new TerminateSessionCommand(operator, CLIENT, target);
  }

  private DomainException rejection(Runnable action) {
    try {
      action.run();
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
  void terminatesOnlyTheTargetSession() {
    Session target = newSession("zhangsan-a");
    Session sameUserOther = newSession("zhangsan-b");
    Session stranger = newSession("lisi");

    useCase.terminate(command(target.ref()));

    assertThat(sessions.byHash.values()).containsExactlyInAnyOrder(sameUserOther, stranger);
    assertThat(sessions.terminated).containsExactly(target.ref());
  }

  @Test
  void terminatingTheOperatorsOwnSessionIsRejectedBeforeTouchingAnything() {
    Session own = newSession("ops");
    operator = new OperatorContext(own.userId(), own.accountId(), own.ref().value());

    DomainException e = rejection(() -> useCase.terminate(command(own.ref())));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.CANNOT_TERMINATE_OWN_SESSION);
    assertThat(sessions.terminated).as("连终止都不尝试").isEmpty();
    assertThat(sessions.byHash.values()).containsExactly(own);
  }

  @Test
  void anOwnSessionThatAlreadyEndedIsStillRejectedAsOwnNotAsNotFound() {
    SessionRef gone = SessionRef.random();
    operator = new OperatorContext(UUID.randomUUID(), UUID.randomUUID(), gone.value());

    DomainException e = rejection(() -> useCase.terminate(command(gone)));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.CANNOT_TERMINATE_OWN_SESSION);
  }

  @Test
  void anUnknownOrAlreadyEndedSessionIsNotFound() {
    Session ended = newSession("zhangsan-a");
    sessions.byHash.clear();

    DomainException e = rejection(() -> useCase.terminate(command(ended.ref())));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    assertThat(rejection(() -> useCase.terminate(command(SessionRef.random()))).errorCode())
        .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
  }

  @Test
  void terminatingTwiceFailsTheSecondTimeWithNotFound() {
    Session target = newSession("zhangsan-a");
    useCase.terminate(command(target.ref()));

    DomainException e = rejection(() -> useCase.terminate(command(target.ref())));

    assertThat(e.errorCode()).isEqualTo(ErrorCode.SESSION_NOT_FOUND);
  }

  @Test
  void successWritesASessionTerminateEventWithOperatorClientAndTargetRef() {
    Session target = newSession("zhangsan-a");

    useCase.terminate(command(target.ref()));

    WriteOperationEvent event = onlyEvent();
    assertThat(event.action()).isEqualTo(Action.SESSION_TERMINATE);
    assertThat(event.outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(event.operator()).isEqualTo(operator);
    assertThat(event.clientId()).isEqualTo(CLIENT);
    assertThat(event.targetType()).isEqualTo("session");
    assertThat(event.targetId()).isEqualTo(target.ref().toString());
    assertThat(event.reason()).isNull();
  }

  @Test
  void rejectionsWriteARejectedEventWithTheErrorCodeAsReason() {
    Session own = newSession("ops");
    operator = new OperatorContext(own.userId(), own.accountId(), own.ref().value());
    SessionRef unknown = SessionRef.random();

    rejection(() -> useCase.terminate(command(own.ref())));
    rejection(() -> useCase.terminate(command(unknown)));

    assertThat(writeLog.events).hasSize(2);
    assertThat(writeLog.events)
        .allSatisfy(
            event -> {
              assertThat(event.action()).isEqualTo(Action.SESSION_TERMINATE);
              assertThat(event.outcome()).isEqualTo(Outcome.REJECTED);
              assertThat(event.targetType()).isEqualTo("session");
            });
    assertThat(writeLog.events.get(0).reason()).isEqualTo("cannot_terminate_own_session");
    assertThat(writeLog.events.get(0).targetId()).isEqualTo(own.ref().toString());
    assertThat(writeLog.events.get(1).reason()).isEqualTo("session_not_found");
    assertThat(writeLog.events.get(1).targetId()).isEqualTo(unknown.toString());
  }

  @Test
  void anUnexpectedFailureIsLoggedAsFailedAndRethrown() {
    FakeSessionStore broken =
        new FakeSessionStore() {
          @Override
          public boolean terminateByRef(SessionRef ref) {
            throw new IllegalStateException("redis down");
          }
        };
    TerminateSessionUseCase failing = new TerminateSessionUseCase(broken, writeLog);

    assertThatThrownBy(() -> failing.terminate(command(SessionRef.random())))
        .isInstanceOf(IllegalStateException.class);

    WriteOperationEvent event = onlyEvent();
    assertThat(event.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(event.reason()).isEqualTo("internal_error");
  }
}
