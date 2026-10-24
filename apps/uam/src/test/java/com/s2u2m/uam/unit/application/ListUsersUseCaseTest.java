package com.s2u2m.uam.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.application.ListUsersUseCase;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.PageResult;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.model.UserWithAccountCount;
import com.s2u2m.uam.support.Fakes.FakeUsers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T079：列表用例——搜索词去首尾空白、空白视为不搜索；分页参数按契约校验。 */
class ListUsersUseCaseTest {

  private final FakeUsers users = new FakeUsers();
  private final ListUsersUseCase useCase = new ListUsersUseCase(users);

  @Test
  void passesPagingThroughAndEchoesItWithTheTotal() {
    var row =
        new UserWithAccountCount(
            new User(UUID.randomUUID(), "张三", UserStatus.ACTIVE, Instant.EPOCH), 2);
    users.searchResult = new PageResult<>(List.of(row), 41);

    var result = useCase.list(3, 10, null);

    assertThat(users.lastPage).isEqualTo(3);
    assertThat(users.lastSize).isEqualTo(10);
    assertThat(result.items()).containsExactly(row);
    assertThat(result.page()).isEqualTo(3);
    assertThat(result.size()).isEqualTo(10);
    assertThat(result.total()).isEqualTo(41);
  }

  @Test
  void trimsTheQuery() {
    useCase.list(1, 20, "  Alice \t");

    assertThat(users.lastQuery).isEqualTo("Alice");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t\n"})
  void aBlankQueryMeansNoSearch(String blank) {
    useCase.list(1, 20, blank);

    assertThat(users.lastQuery).isNull();
  }

  @Test
  void aMissingQueryMeansNoSearch() {
    useCase.list(1, 20, null);

    assertThat(users.lastQuery).isNull();
  }

  @Test
  void acceptsTheContractBounds() {
    useCase.list(1, 1, "a".repeat(64));
    useCase.list(Integer.MAX_VALUE, 100, null);

    assertThat(users.lastSize).isEqualTo(100);
  }

  @Test
  void rejectsOutOfRangePagingAndTooLongQueryFieldByField() {
    assertThatThrownBy(() -> useCase.list(0, 101, "a".repeat(65)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.fieldErrors())
                  .extracting(f -> f.field() + ":" + f.reason())
                  .containsExactly("page:invalid", "size:invalid", "query:too_long");
            });
    assertThat(users.lastQuery).isEqualTo("<never called>");
  }

  @Test
  void aQueryWithNulOrAnUnpairedSurrogateIsInvalidChars() {
    for (String bad : List.of("a\u0000b", "\u0000", "ab\uD800", "\uDC00ab")) {
      assertThatThrownBy(() -> useCase.list(1, 20, bad))
          .isInstanceOfSatisfying(
              DomainException.class,
              e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(e.fieldErrors())
                    .extracting(f -> f.field() + ":" + f.reason())
                    .containsExactly("query:invalid_chars");
              });
    }
    assertThat(users.lastQuery).isEqualTo("<never called>");
    useCase.list(1, 20, "emoji\uD83D\uDE00");
    assertThat(users.lastQuery).isEqualTo("emoji\uD83D\uDE00");
  }

  @Test
  void sizeZeroAndNegativePageAreRejected() {
    assertThatThrownBy(() -> useCase.list(-1, 20, null)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> useCase.list(1, 0, null)).isInstanceOf(DomainException.class);
  }
}
