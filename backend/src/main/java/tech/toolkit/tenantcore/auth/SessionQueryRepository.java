package tech.toolkit.tenantcore.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Repository;
import tech.toolkit.tenantcore.identity.Account;
import tech.toolkit.tenantcore.platform.PageResult;

/** 本租户当前有效会话的分页查询（跨用户、跨 Account）；租户条件由 {@code @TenantId} 与 RLS 保证。 */
@Repository
class SessionQueryRepository {

  record Row(Session session, String displayName, String accountIdentifier) {}

  @PersistenceContext private EntityManager em;

  PageResult<Row> activePage(
      String keyword, Instant notExpiredAfter, Instant activeAfter, int page, int size) {
    boolean hasKeyword = keyword != null && !keyword.isBlank();
    String from =
        " from Session s, tech.toolkit.tenantcore.identity.AppUser u,"
            + " tech.toolkit.tenantcore.identity.Account a"
            + " where s.userId = u.id and s.accountId = a.id and s.revokedAt is null"
            + " and s.absoluteExpiresAt > :now and s.lastSeenAt > :idleCutoff"
            + (hasKeyword
                ? " and (lower(u.displayName) like :like escape '\\'"
                    + " or a.identifierNormalized like :nlike escape '\\')"
                : "");
    TypedQuery<Object[]> query =
        em.createQuery(
            "select s, u.displayName, a.identifier" + from + " order by s.lastSeenAt desc, s.id",
            Object[].class);
    TypedQuery<Long> count = em.createQuery("select count(s)" + from, Long.class);
    for (TypedQuery<?> q : List.of(query, count)) {
      q.setParameter("now", notExpiredAfter);
      q.setParameter("idleCutoff", activeAfter);
      if (hasKeyword) {
        String k = keyword.trim();
        q.setParameter("like", escape(k.toLowerCase(Locale.ROOT)) + "%");
        q.setParameter("nlike", escape(Account.normalize(k)) + "%");
      }
    }
    List<Row> rows =
        query.setFirstResult(page * size).setMaxResults(size).getResultList().stream()
            .map(r -> new Row((Session) r[0], (String) r[1], (String) r[2]))
            .toList();
    return new PageResult<>(rows, page, size, count.getSingleResult());
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
