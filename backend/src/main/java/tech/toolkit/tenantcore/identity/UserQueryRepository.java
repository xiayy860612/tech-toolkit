package tech.toolkit.tenantcore.identity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.springframework.stereotype.Repository;
import tech.toolkit.tenantcore.platform.PageResult;

/** User 列表查询（关键词匹配显示名或用户名前缀、状态筛选、分页）；租户条件由 {@code @TenantId} 与 RLS 保证。 */
@Repository
public class UserQueryRepository {

  @PersistenceContext private EntityManager em;

  public PageResult<AppUser> page(String keyword, Status status, int page, int size) {
    StringBuilder where = new StringBuilder(" where 1 = 1");
    if (status != null) {
      where.append(" and u.status = :status");
    }
    boolean hasKeyword = keyword != null && !keyword.isBlank();
    if (hasKeyword) {
      where.append(
          " and (lower(u.displayName) like :like escape '\\' or u.usernameNormalized like :nlike"
              + " escape '\\')");
    }
    TypedQuery<AppUser> query =
        em.createQuery(
            "select u from AppUser u" + where + " order by u.displayName, u.id", AppUser.class);
    TypedQuery<Long> count = em.createQuery("select count(u) from AppUser u" + where, Long.class);
    for (TypedQuery<?> q : List.of(query, count)) {
      if (status != null) {
        q.setParameter("status", status);
      }
      if (hasKeyword) {
        String trimmed = keyword.trim();
        q.setParameter("like", escape(trimmed.toLowerCase(java.util.Locale.ROOT)) + "%");
        q.setParameter("nlike", escape(IdentityRules.normalizeUsername(trimmed)) + "%");
      }
    }
    List<AppUser> items = query.setFirstResult(page * size).setMaxResults(size).getResultList();
    return new PageResult<>(items, page, size, count.getSingleResult());
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
