package tech.toolkit.tenantcore.api;

import java.util.ArrayList;
import java.util.List;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ApiException.FieldError;
import tech.toolkit.tenantcore.platform.ErrorCode;

/** 分页与关键词参数的统一校验（page ≥ 0，size 1–100，keyword ≤ 50）。 */
record PageParams(int page, int size) {

  static PageParams of(Integer page, Integer size) {
    int p = page == null ? 0 : page;
    int s = size == null ? 20 : size;
    List<FieldError> errors = new ArrayList<>();
    if (p < 0) {
      errors.add(new FieldError("page", "page 不能小于 0"));
    }
    if (s < 1 || s > 100) {
      errors.add(new FieldError("size", "size 取值 1–100"));
    }
    throwIfAny(errors);
    return new PageParams(p, s);
  }

  static String keyword(String keyword) {
    if (keyword != null && keyword.length() > 50) {
      throwIfAny(List.of(new FieldError("keyword", "keyword 不超过 50 个字符")));
    }
    return keyword;
  }

  private static void throwIfAny(List<FieldError> errors) {
    if (!errors.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.title(), errors);
    }
  }
}
