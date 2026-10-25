package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.VerifySessionUseCase;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.AccountKind;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T047：校验会话用例（假端口）。 */
class VerifySessionUseCaseTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");

  private final FakeClock clock = new FakeClock(T0);
  private final FakeSessionStore sessions = new FakeSessionStore();
  private final SessionPolicy policy =
      new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12), Duration.ofSeconds(60));
  private final VerifySessionUseCase useCase = new VerifySessionUseCase(sessions, policy, clock);

  private String sessionId;
  private Session stored;

  @BeforeEach
  void setUp() {
    SessionToken token = SessionToken.generate();
    sessionId = token.id();
    User user = new User(UUID.randomUUID(), "爱丽丝", UserStatus.ACTIVE, T0);
    stored =
        new Session(
            SessionRef.random(),
            user.id(),
            user.name(),
            UUID.randomUUID(),
            "Alice",
            AccountKind.PASSWORD,
            T0,
            T0,
            policy.absoluteExpiry(T0),
            policy.expiry(T0, policy.absoluteExpiry(T0)));
    sessions.byHash.put(token.hash(), stored);
  }

  private void assertSessionInvalid(String id) {
    assertThatThrownBy(() -> useCase.verify(id))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SESSION_INVALID));
  }

  @Test
  void validSessionReturnsItsData() {
    clock.advance(Duration.ofSeconds(10));

    Session session = useCase.verify(sessionId);

    assertThat(session.userId()).isEqualTo(stored.userId());
    assertThat(session.userName()).isEqualTo("爱丽丝");
    assertThat(session.accountId()).isEqualTo(stored.accountId());
    assertThat(session.loginName()).isEqualTo("Alice");
    assertThat(session.kind()).isEqualTo(AccountKind.PASSWORD);
    assertThat(session.ref()).isEqualTo(stored.ref());
    assertThat(session.expiresAt()).isEqualTo(stored.expiresAt());
  }

  @Test
  void unknownSessionIsInvalid() {
    assertSessionInvalid(SessionToken.generate().id());
  }

  @Test
  void nullBlankOrOverlongSessionIdIsInvalidNotABadRequest() {
    assertSessionInvalid(null);
    assertSessionInvalid("   ");
    assertSessionInvalid("x".repeat(129));
  }

  @Test
  void expiredSessionIsInvalid() {
    clock.advance(Duration.ofMinutes(31));

    assertSessionInvalid(sessionId);
  }

  @Test
  void supersededSessionIsInvalid() {
    sessions.byHash.clear(); // 同账号再次登录，旧会话已被顶掉

    assertSessionInvalid(sessionId);
  }

  private void assertSessionInvalidWithReason(String id, java.util.Optional<String> reason) {
    assertThatThrownBy(() -> useCase.verify(id))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.SESSION_INVALID);
              assertThat(e.reason()).isEqualTo(reason);
            });
  }

  @Test
  void aSessionForceTerminatedByAnAdministratorIsInvalidWithReasonTerminated() {
    sessions.terminateByRef(stored.ref(), true);

    assertSessionInvalidWithReason(sessionId, java.util.Optional.of("terminated"));
  }

  @Test
  void aSessionEndedInAnyOtherWayIsInvalidWithoutAReason() {
    sessions.terminateByRef(stored.ref(), false); // 退出、停用、登录回滚：不留墓碑

    assertSessionInvalidWithReason(sessionId, java.util.Optional.empty());
    assertSessionInvalidWithReason(SessionToken.generate().id(), java.util.Optional.empty());
    assertSessionInvalidWithReason(null, java.util.Optional.empty());
    assertSessionInvalidWithReason("x".repeat(129), java.util.Optional.empty());
  }

  @Test
  void anExpiredSessionWithoutATombstoneHasNoReason() {
    clock.advance(Duration.ofMinutes(31));

    assertSessionInvalidWithReason(sessionId, java.util.Optional.empty());
  }

  @Test
  void aLiveSessionIsNeverAffectedByAnUnrelatedTombstone() {
    sessions.tombstones.add(SessionToken.generate().hash());

    assertThat(useCase.verify(sessionId).ref()).isEqualTo(stored.ref());
  }

  @Test
  void noTouchWithinTheTouchInterval() {
    clock.advance(Duration.ofSeconds(60));

    Session session = useCase.verify(sessionId);

    assertThat(sessions.touched).isEmpty();
    assertThat(session.lastActiveAt()).isEqualTo(T0);
  }

  @Test
  void touchesAfterMoreThanSixtySecondsAndExtendsTheIdleDeadline() {
    clock.advance(Duration.ofSeconds(61));
    Instant now = clock.now();

    Session session = useCase.verify(sessionId);

    assertThat(sessions.touched).hasSize(1);
    assertThat(session.lastActiveAt()).isEqualTo(now);
    assertThat(session.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    assertThat(session.absoluteExpiresAt()).isEqualTo(stored.absoluteExpiresAt());
    assertThat(session.loginAt()).isEqualTo(T0);
  }

  @Test
  void touchNeverExtendsPastTheAbsoluteLimit() {
    Instant lastActive = T0.plus(Duration.ofHours(11)).plus(Duration.ofMinutes(40));
    SessionToken late = SessionToken.generate();
    sessions.byHash.put(
        late.hash(),
        new Session(
            SessionRef.random(),
            stored.userId(),
            stored.userName(),
            UUID.randomUUID(),
            "Bob",
            AccountKind.PASSWORD,
            T0,
            lastActive,
            stored.absoluteExpiresAt(),
            policy.expiry(lastActive, stored.absoluteExpiresAt())));
    clock.advance(Duration.ofHours(11).plusMinutes(40).plusSeconds(61));

    Session fresh = useCase.verify(late.id());

    assertThat(fresh.lastActiveAt()).isEqualTo(clock.now());
    assertThat(fresh.expiresAt()).isEqualTo(stored.absoluteExpiresAt());
  }

  @Test
  void sessionVanishingBetweenReadAndTouchIsInvalid() {
    FakeSessionStore vanishing =
        new FakeSessionStore() {
          @Override
          public java.util.Optional<Session> touch(String tokenHash, Session touched) {
            byHash.clear();
            return super.touch(tokenHash, touched);
          }
        };
    vanishing.byHash.putAll(sessions.byHash);
    VerifySessionUseCase racing = new VerifySessionUseCase(vanishing, policy, clock);
    clock.advance(Duration.ofSeconds(120));

    assertThatThrownBy(() -> racing.verify(sessionId))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SESSION_INVALID));
  }
}
