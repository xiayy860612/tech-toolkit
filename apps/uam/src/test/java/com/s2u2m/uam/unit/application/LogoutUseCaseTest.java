package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.LogoutUseCase;
import com.s2u2m.uam.application.VerifySessionUseCase;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.SessionToken;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.policy.SessionPolicy;
import com.s2u2m.uam.support.Fakes.FakeClock;
import com.s2u2m.uam.support.Fakes.FakeSessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T144（退出部分）：退出用例（假端口）——只终止该标识对应的这一条会话；未知、已到期、已退出、空白、过长的标识一律静默成功（幂等）；退出后同一标识的校验为 {@code
 * session_invalid}。
 */
class LogoutUseCaseTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
  private static final SessionPolicy POLICY =
      new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12), Duration.ofSeconds(60));

  private final FakeClock clock = new FakeClock(T0);
  private final FakeSessionStore sessions = new FakeSessionStore();
  private final LogoutUseCase useCase = new LogoutUseCase(sessions);
  private final VerifySessionUseCase verify = new VerifySessionUseCase(sessions, POLICY, clock);

  private record Started(Session session, SessionToken token) {}

  private Started newSession(String loginName) {
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
    SessionToken token = SessionToken.generate();
    sessions.create(session, token.hash());
    return new Started(session, token);
  }

  @Test
  void logoutTerminatesOnlyThatSession() {
    Started mine = newSession("alice");
    Started others = newSession("bob");

    useCase.logout(mine.token().id());

    assertThat(sessions.terminated).containsExactly(mine.session().ref());
    assertThat(sessions.byHash).doesNotContainKey(mine.token().hash());
    assertThat(sessions.byHash).containsKey(others.token().hash());
  }

  @Test
  void logoutNeverLeavesATombstoneSoTheLaterVerifyHasNoReason() {
    Started mine = newSession("alice");

    useCase.logout(mine.token().id());

    assertThat(sessions.terminatedMarks).containsExactly(false);
    assertThat(sessions.tombstones).isEmpty();
    assertThatThrownBy(() -> verify.verify(mine.token().id()))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.reason()).isEmpty());
  }

  @Test
  void theSameIdIsInvalidAfterLogout() {
    Started mine = newSession("alice");
    assertThat(verify.verify(mine.token().id()).ref()).isEqualTo(mine.session().ref());

    useCase.logout(mine.token().id());

    assertThatThrownBy(() -> verify.verify(mine.token().id()))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SESSION_INVALID));
  }

  @Test
  void logoutIsIdempotent() {
    Started mine = newSession("alice");

    useCase.logout(mine.token().id());

    assertThatCode(() -> useCase.logout(mine.token().id())).doesNotThrowAnyException();
    assertThat(sessions.terminated).as("第二次不再终止任何会话").hasSize(1);
  }

  @Test
  void anUnknownSessionIdIsANoOp() {
    Started others = newSession("bob");

    assertThatCode(() -> useCase.logout(SessionToken.generate().id())).doesNotThrowAnyException();

    assertThat(sessions.terminated).isEmpty();
    assertThat(sessions.byHash).containsKey(others.token().hash());
  }

  @Test
  void blankNullOrOverlongIdsAreANoOpWithoutTouchingTheStore() {
    Started others = newSession("bob");

    for (String id : new String[] {null, "", "   ", "x".repeat(129)}) {
      assertThatCode(() -> useCase.logout(id)).doesNotThrowAnyException();
    }

    assertThat(sessions.terminated).isEmpty();
    assertThat(sessions.byHash).containsKey(others.token().hash());
  }

  @Test
  void aSessionPastItsExpiryButStillStoredIsTerminatedToo() {
    Started mine = newSession("alice");
    clock.advance(Duration.ofHours(13));

    useCase.logout(mine.token().id());

    assertThat(sessions.byHash).doesNotContainKey(mine.token().hash());
  }
}
