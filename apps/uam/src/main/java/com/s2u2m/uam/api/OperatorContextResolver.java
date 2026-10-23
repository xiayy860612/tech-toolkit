package com.s2u2m.uam.api;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.OperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 解析操作人上下文（{@code X-Operator-User-Id} / {@code -Account-Id} / {@code -Session-Ref}，均为 UUID）。
 *
 * <p>写操作（非 GET / HEAD / OPTIONS）三项必须齐全，否则 400 {@code operator_required}；登录相关接口（{@code
 * /v1/auth/**}）除外。 读操作可选，但带了就必须是合法 UUID。UAM 信任这些值，只用于自我保护规则与写操作日志（research R5）。
 */
@Component
public class OperatorContextResolver {

  static final String USER_ID = "X-Operator-User-Id";
  static final String ACCOUNT_ID = "X-Operator-Account-Id";
  static final String SESSION_REF = "X-Operator-Session-Ref";

  private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
  private static final String AUTH_PREFIX = "/v1/auth/";

  public Optional<OperatorContext> resolve(HttpServletRequest request) {
    UUID userId = parse(request, USER_ID);
    UUID accountId = parse(request, ACCOUNT_ID);
    UUID sessionRef = parse(request, SESSION_REF);
    if (isWriteRequiringOperator(request)
        && (userId == null || accountId == null || sessionRef == null)) {
      throw operatorRequired();
    }
    if (userId == null && accountId == null && sessionRef == null) {
      return Optional.empty();
    }
    return Optional.of(new OperatorContext(userId, accountId, sessionRef));
  }

  private static boolean isWriteRequiringOperator(HttpServletRequest request) {
    return !READ_METHODS.contains(request.getMethod().toUpperCase(java.util.Locale.ROOT))
        && !request.getRequestURI().startsWith(AUTH_PREFIX);
  }

  private static UUID parse(HttpServletRequest request, String header) {
    String value = request.getHeader(header);
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw operatorRequired();
    }
  }

  private static DomainException operatorRequired() {
    return new DomainException(ErrorCode.OPERATOR_REQUIRED, "缺少或不合法的操作人请求头（X-Operator-*）");
  }
}
