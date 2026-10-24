package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.model.UserWithAccountCount;
import com.s2u2m.uam.domain.policy.TextChars;
import com.s2u2m.uam.domain.port.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 分页列出用户并按用户名或账号名搜索（FR-017、FR-018）。
 *
 * <p>分页参数按契约校验（页码 &gt;= 1，每页 1–100），越界返回 {@code validation_failed} 而不是悄悄截断；搜索词去首尾空白，为空视为不搜索。
 */
@Component
@Profile("!cli")
public class ListUsersUseCase {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 100;
  public static final int MAX_QUERY_LENGTH = 64;

  /** 一页用户。 */
  public record Result(List<UserWithAccountCount> items, int page, int size, long total) {}

  private final UserRepository users;

  public ListUsersUseCase(UserRepository users) {
    this.users = users;
  }

  public Result list(int page, int size, String query) {
    validate(page, size, query);
    String term = query == null ? "" : query.strip();
    var found = users.search(term.isEmpty() ? null : term, page, size);
    return new Result(found.items(), page, size, found.total());
  }

  private static void validate(int page, int size, String query) {
    List<FieldError> errors = new ArrayList<>();
    if (page < 1) {
      errors.add(new FieldError("page", "invalid"));
    }
    if (size < 1 || size > MAX_SIZE) {
      errors.add(new FieldError("size", "invalid"));
    }
    if (query != null && query.length() > MAX_QUERY_LENGTH) {
      errors.add(new FieldError("query", "too_long"));
    } else if (query != null && TextChars.hasUnsafeChars(query)) {
      errors.add(new FieldError("query", "invalid_chars"));
    }
    if (!errors.isEmpty()) {
      throw DomainException.validationFailed(errors);
    }
  }
}
