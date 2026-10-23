package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.ListOnlineUsersUseCase;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.OnlineUserSummary;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.support.Fakes.FakeClock;
import com.s2u2m.uam.support.Fakes.FakeSessionStore;
import com.s2u2m.uam.support.Fakes.FakeUsers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** T091：在线用户用例——按页切分已聚合并排序的在线列表，用户名回用户仓储批量取；分页参数按契约校验。 */
class ListOnlineUsersUseCaseTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");

  private final FakeClock clock = new FakeClock(T0);
  private final FakeSessionStore sessions = new FakeSessionStore();
  private final FakeUsers users = new FakeUsers();
  private final ListOnlineUsersUseCase useCase = new ListOnlineUsersUseCase(sessions, users, clock);

  private List<OnlineUserSummary> seed(int count) {
    List<OnlineUserSummary> summaries = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      UUID id = UUID.randomUUID();
      users.insert(new User(id, "用户" + i, UserStatus.ACTIVE, Instant.EPOCH));
      summaries.add(new OnlineUserSummary(id, i + 1, T0.minusSeconds(i)));
    }
    sessions.online = summaries;
    return summaries;
  }

  @Test
  void asksTheStoreAboutTheCurrentInstant() {
    seed(1);

    useCase.list(1, 20);

    assertThat(sessions.listOnlineNow).isEqualTo(T0);
  }

  @Test
  void returnsTheRequestedPageWithNamesFromTheUserRepositoryInOneBatch() {
    List<OnlineUserSummary> all = seed(5);

    var result = useCase.list(2, 2);

    assertThat(result.items()).hasSize(2);
    assertThat(result.items().get(0).userId()).isEqualTo(all.get(2).userId());
    assertThat(result.items().get(0).userName()).isEqualTo("用户2");
    assertThat(result.items().get(0).sessionCount()).isEqualTo(3);
    assertThat(result.items().get(0).lastActiveAt()).isEqualTo(all.get(2).lastActiveAt());
    assertThat(result.items().get(1).userName()).isEqualTo("用户3");
    assertThat(result.page()).isEqualTo(2);
    assertThat(result.size()).isEqualTo(2);
    assertThat(result.total()).isEqualTo(5);
    assertThat(users.findNamesCalls).isEqualTo(1);
    assertThat(users.lastFindNamesIds)
        .containsExactlyInAnyOrder(all.get(2).userId(), all.get(3).userId());
  }

  @Test
  void aPageBeyondTheEndIsEmptyButKeepsTheTotalAndSkipsTheNameLookup() {
    seed(3);

    var result = useCase.list(5, 2);

    assertThat(result.items()).isEmpty();
    assertThat(result.total()).isEqualTo(3);
    assertThat(users.findNamesCalls).isZero();
  }

  @Test
  void anEmptyOnlineListIsAnEmptyPage() {
    var result = useCase.list(1, 20);

    assertThat(result.items()).isEmpty();
    assertThat(result.total()).isZero();
  }

  @Test
  void aUserMissingFromThePostgreSqlFallsBackToTheUserId() {
    UUID ghost = UUID.randomUUID();
    sessions.online = List.of(new OnlineUserSummary(ghost, 1, T0));

    var result = useCase.list(1, 20);

    assertThat(result.items().get(0).userName()).isEqualTo(ghost.toString());
  }

  @ParameterizedTest
  @CsvSource({"0,20,page", "-1,20,page", "1,0,size", "1,101,size"})
  void outOfRangePagingIsAFieldLevelValidationFailure(int page, int size, String field) {
    assertThatThrownBy(() -> useCase.list(page, size))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.fieldErrors())
                  .containsExactly(new DomainException.FieldError(field, "invalid"));
            });
  }

  @Test
  void bothInvalidParametersAreReported() {
    assertThatThrownBy(() -> useCase.list(0, 0))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.fieldErrors()).hasSize(2));
  }
}
