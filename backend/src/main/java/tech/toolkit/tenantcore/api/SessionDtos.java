package tech.toolkit.tenantcore.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tech.toolkit.tenantcore.auth.Session;
import tech.toolkit.tenantcore.auth.SessionAdminService;
import tech.toolkit.tenantcore.platform.PageResult;

/** /sessions 的响应（与 contracts/openapi.yaml 一致）：绝不含令牌、令牌哈希或原始 User-Agent。 */
public final class SessionDtos {

  private SessionDtos() {}

  public record LoginSession(
      UUID id,
      Long userId,
      String displayName,
      String username,
      String loginType,
      String device,
      String ip,
      Instant createdAt,
      Instant lastSeenAt,
      Instant expiresAt,
      boolean current) {

    static LoginSession of(SessionAdminService.Item i) {
      Session s = i.session();
      return new LoginSession(
          s.getId(),
          s.getUserId(),
          i.displayName(),
          i.username(),
          s.getLoginType().name(),
          s.getDevice(),
          s.getIp() == null ? "" : s.getIp().getHostAddress(),
          s.getCreatedAt(),
          s.getLastSeenAt(),
          i.expiresAt(),
          i.current());
    }
  }

  public record LoginSessionPage(List<LoginSession> items, int page, int size, long total) {

    static LoginSessionPage of(PageResult<SessionAdminService.Item> p) {
      return new LoginSessionPage(
          p.items().stream().map(LoginSession::of).toList(), p.page(), p.size(), p.total());
    }
  }
}
