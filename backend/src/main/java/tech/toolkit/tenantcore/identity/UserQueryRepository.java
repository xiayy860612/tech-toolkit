package tech.toolkit.tenantcore.identity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
          " and (lower(u.displayName) like :like escape '\\' or exists (select 1 from Account a"
              + " where a.userId = u.id and a.identifierNormalized like :nlike escape '\\'))");
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
        q.setParameter("nlike", escape(Account.normalize(trimmed)) + "%");
      }
    }
    List<AppUser> items = query.setFirstResult(page * size).setMaxResults(size).getResultList();
    return new PageResult<>(items, page, size, count.getSingleResult());
  }

  /** 为一批 User 汇总 Account 数、主用户名（最早创建的可用 Account，没有可用的则取最早的）与最近登录。 */
  public List<UserView> views(List<AppUser> users) {
    if (users.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = users.stream().map(AppUser::getId).toList();
    Map<UUID, List<Account>> byUser = new HashMap<>();
    em.createQuery(
            "select a from Account a where a.userId in :ids order by a.createdAt, a.id",
            Account.class)
        .setParameter("ids", ids)
        .getResultList()
        .forEach(
            a -> byUser.computeIfAbsent(a.getUserId(), k -> new java.util.ArrayList<>()).add(a));
    return users.stream().map(u -> view(u, byUser.getOrDefault(u.getId(), List.of()))).toList();
  }

  public UserView view(AppUser user) {
    return views(List.of(user)).get(0);
  }

  private static UserView view(AppUser user, List<Account> accounts) {
    String primary =
        accounts.stream()
            .filter(a -> a.getStatus() == Status.ACTIVE)
            .findFirst()
            .or(() -> accounts.stream().findFirst())
            .map(Account::getIdentifier)
            .orElse(null);
    Instant last =
        accounts.stream()
            .map(Account::getLastLoginAt)
            .filter(java.util.Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    return new UserView(user, accounts.size(), primary, last);
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
