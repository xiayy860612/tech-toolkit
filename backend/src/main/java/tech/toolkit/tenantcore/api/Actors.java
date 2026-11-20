package tech.toolkit.tenantcore.api;

import jakarta.servlet.http.HttpServletRequest;
import tech.toolkit.tenantcore.api.security.AuthenticatedUser;
import tech.toolkit.tenantcore.platform.Actor;

/** 把已验签的当前用户转成业务层的 {@link Actor}（审计与自我保护用）。 */
final class Actors {

  private Actors() {}

  static Actor of(HttpServletRequest request) {
    AuthenticatedUser u = AuthenticatedUser.of(request);
    return new Actor(u.userId(), u.accountId(), u.clientIp());
  }
}
