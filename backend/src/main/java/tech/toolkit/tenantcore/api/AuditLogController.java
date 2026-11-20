package tech.toolkit.tenantcore.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.toolkit.tenantcore.audit.AuditLog;
import tech.toolkit.tenantcore.audit.AuditQueryService;
import tech.toolkit.tenantcore.platform.PageResult;

@RestController
public class AuditLogController {

  public record AuditLogItem(
      UUID id,
      Instant occurredAt,
      UUID actorUserId,
      UUID actorAccountId,
      String action,
      String targetType,
      UUID targetId,
      String result,
      String reason,
      String ip) {

    static AuditLogItem of(AuditLog l) {
      return new AuditLogItem(
          l.getId(),
          l.getOccurredAt(),
          l.getActorUserId(),
          l.getActorAccountId(),
          l.getAction().name(),
          l.getTargetType() == null ? null : l.getTargetType().name(),
          l.getTargetId(),
          l.getResult(),
          l.getReason(),
          l.getIp() == null ? null : l.getIp().getHostAddress());
    }
  }

  public record AuditLogPage(List<AuditLogItem> items, int page, int size, long total) {}

  private final AuditQueryService service;

  public AuditLogController(AuditQueryService service) {
    this.service = service;
  }

  @GetMapping("/audit-logs")
  AuditLogPage list(
      @RequestParam(required = false) String action,
      @RequestParam(required = false) UUID actorUserId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    PageParams p = PageParams.of(page, size);
    PageResult<AuditLog> found = service.search(action, actorUserId, from, to, p.page(), p.size());
    return new AuditLogPage(
        found.items().stream().map(AuditLogItem::of).toList(), p.page(), p.size(), found.total());
  }
}
