package com.s2u2m.uam.domain.model;

import java.util.List;

/** 一页结果：当前页的条目与满足条件的总数。 */
public record PageResult<T>(List<T> items, long total) {

  public PageResult {
    items = List.copyOf(items);
  }
}
