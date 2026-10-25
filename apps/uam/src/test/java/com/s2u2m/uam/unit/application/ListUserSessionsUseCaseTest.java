package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.ListUserSessionsUseCase;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.support.Fakes.FakeAccounts;
import com.s2u2m.uam.support.Fakes.FakeClock;
import com.s2u2m.uam.support.Fakes.FakeSessionStore;
import com.s2u2m.uam.support.Fakes.FakeUsers;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T091：用户会话用例——取其全部账号当前指向的会话，标注 {@code current}；用户不存在抛 {@code user_not_found}。 */
class ListUserSessionsUseCaseTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");

  private final FakeClock clock = new FakeClock(T0);
  private final FakeUsers users = new FakeUsers();
  private final FakeAccounts accounts = new FakeAccounts();
  private final FakeSessionStore sessions = new FakeSessionStore();
  private final ListUserSessionsUseCase useCase =
      new ListUserSessionsUseCase(users, accounts, sessions, clock);

  private User user(String name) {
    var user = new User(UUID.randomUUID(), name, UserStatus.ACTIVE, Instant.EPOCH);
    users.insert(user);
    return user;
  }

  private Account account(User owner, String loginName) {
    var account =
        new Account(
            UUID.randomUUID(),
            owner.id(),
            AccountKind.PASSWORD,
            LoginName.of(loginName),
            AccountStatus.ACTIVE,
            Instant.EPOCH);
    accounts.insert(account);
    return account;
  }

  private Session session(User owner, Account account, Instant expiresAt) {
    var session =
        new Session(
            SessionRef.random(),
            owner.id(),
            owner.name(),
            account.id(),
            account.loginName().raw(),
            account.kind(),
            T0.minusSeconds(300),
            T0.minusSeconds(10),
            T0.plus(Duration.ofHours(10)),
            expiresAt);
    sessions.create(session, "hash-" + session.ref());
    return session;
  }

  @Test
  void listsTheSessionOfEachAccountOfTheUserOnly() {
    User alice = user("爱丽丝");
    User bob = user("鲍勃");
    Session first = session(alice, account(alice, "alice"), T0.plusSeconds(600));
    Session second = session(alice, account(alice, "alice.work"), T0.plusSeconds(900));
    session(bob, account(bob, "bob"), T0.plusSeconds(600));

    var items = useCase.list(alice.id(), null);

    assertThat(items)
        .extracting(ListUserSessionsUseCase.Item::session)
        .containsExactly(first, second);
    assertThat(items).extracting(ListUserSessionsUseCase.Item::current).containsOnly(false);
  }

  @Test
  void anAccountWithoutASessionContributesNothing() {
    User alice = user("爱丽丝");
    account(alice, "alice");

    assertThat(useCase.list(alice.id(), null)).isEmpty();
  }

  @Test
  void aUserWithoutAccountsHasNoSessions() {
    assertThat(useCase.list(user("孤身一人").id(), null)).isEmpty();
  }

  @Test
  void marksTheOperatorsOwnSessionAsCurrent() {
    User alice = user("爱丽丝");
    Session mine = session(alice, account(alice, "alice"), T0.plusSeconds(600));
    Session other = session(alice, account(alice, "alice.work"), T0.plusSeconds(600));

    var items = useCase.list(alice.id(), mine.ref().value());

    assertThat(items)
        .extracting(ListUserSessionsUseCase.Item::current)
        .containsExactly(true, false);
    assertThat(items.get(1).session()).isEqualTo(other);
  }

  @Test
  void aSessionPastItsExpiryIsDroppedEvenIfTheStoreStillReturnsIt() {
    User alice = user("爱丽丝");
    session(alice, account(alice, "alice"), T0.minusSeconds(1));
    Session live = session(alice, account(alice, "alice.work"), T0.plusSeconds(60));

    assertThat(useCase.list(alice.id(), null))
        .extracting(ListUserSessionsUseCase.Item::session)
        .containsExactly(live);
  }

  @Test
  void anUnknownUserIsNotFound() {
    assertThatThrownBy(() -> useCase.list(UUID.randomUUID(), null))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
  }
}
