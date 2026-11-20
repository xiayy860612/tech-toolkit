package tech.toolkit.tenantcore.platform;

import java.util.List;
import java.util.function.Function;

public record PageResult<T>(List<T> items, int page, int size, long total) {

  public <R> PageResult<R> map(Function<T, R> mapper) {
    return new PageResult<>(items.stream().map(mapper).toList(), page, size, total);
  }
}
