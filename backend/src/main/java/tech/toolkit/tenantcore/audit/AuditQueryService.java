package tech.toolkit.tenantcore.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.tenantcore.platform.PageResult;

/** 本租户审计日志查询（租户条件由 {@code @TenantId} 与 RLS 保证），按发生时间倒序。 */
@Service
public class AuditQueryService {

  @PersistenceContext private EntityManager em;

  /** {@code action} 不是已知动作时返回空页（而不是报错）。 */
  @Transactional(readOnly = true)
  public PageResult<AuditLog> search(
      String action, UUID actorUserId, Instant from, Instant to, int page, int size) {
    AuditAction parsed = null;
    if (action != null && !action.isBlank()) {
      try {
        parsed = AuditAction.valueOf(action);
      } catch (IllegalArgumentException e) {
        return new PageResult<>(List.of(), page, size, 0);
      }
    }
    StringBuilder where = new StringBuilder(" where 1 = 1");
    if (parsed != null) {
      where.append(" and l.action = :action");
    }
    if (actorUserId != null) {
      where.append(" and l.actorUserId = :actor");
    }
    if (from != null) {
      where.append(" and l.occurredAt >= :from");
    }
    if (to != null) {
      where.append(" and l.occurredAt <= :to");
    }
    TypedQuery<AuditLog> query =
        em.createQuery(
            "select l from AuditLog l" + where + " order by l.occurredAt desc, l.id",
            AuditLog.class);
    TypedQuery<Long> count = em.createQuery("select count(l) from AuditLog l" + where, Long.class);
    for (TypedQuery<?> q : List.of(query, count)) {
      if (parsed != null) {
        q.setParameter("action", parsed);
      }
      if (actorUserId != null) {
        q.setParameter("actor", actorUserId);
      }
      if (from != null) {
        q.setParameter("from", from);
      }
      if (to != null) {
        q.setParameter("to", to);
      }
    }
    List<AuditLog> items = query.setFirstResult(page * size).setMaxResults(size).getResultList();
    return new PageResult<>(items, page, size, count.getSingleResult());
  }
}
