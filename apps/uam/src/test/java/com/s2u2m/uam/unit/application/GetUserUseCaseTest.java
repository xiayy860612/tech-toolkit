package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.GetUserUseCase;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.support.Fakes.FakeAccounts;
import com.s2u2m.uam.support.Fakes.FakeUsers;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T079：详情用例——用户及其全部账号；不存在抛 {@code user_not_found}。 */
class GetUserUseCaseTest {

  private final FakeUsers users = new FakeUsers();
  private final FakeAccounts accounts = new FakeAccounts();
  private final GetUserUseCase useCase = new GetUserUseCase(users, accounts);

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

  @Test
  void returnsTheUserWithOnlyItsOwnAccounts() {
    User alice = user("爱丽丝");
    User bob = user("鲍勃");
    Account first = account(alice, "alice");
    Account second = account(alice, "alice.work");
    account(bob, "bob");

    var result = useCase.get(alice.id());

    assertThat(result.user()).isEqualTo(alice);
    assertThat(result.accounts()).containsExactly(first, second);
  }

  @Test
  void aUserWithoutAccountsHasAnEmptyList() {
    User lonely = user("孤身一人");

    assertThat(useCase.get(lonely.id()).accounts()).isEmpty();
  }

  @Test
  void anUnknownUserIsNotFound() {
    assertThatThrownBy(() -> useCase.get(UUID.randomUUID()))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
  }
}
