package tech.toolkit.tenantcore.common;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a list: zero-based {@code page}, {@code size} per page and the overall {@code total}.
 */
public record Page<T>(List<T> items, int page, int size, long total) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 100;

  public <R> Page<R> map(Function<T, R> mapper) {
    return new Page<>(items.stream().map(mapper).toList(), page, size, total);
  }

  /** Clamps request parameters: page &gt;= 0, 1 &lt;= size &lt;= {@value #MAX_SIZE}. */
  public static int normalizeSize(int size) {
    if (size < 1) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }

  public static int normalizePage(int page) {
    return Math.max(page, 0);
  }
}
