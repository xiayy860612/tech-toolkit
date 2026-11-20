package tech.toolkit.tenantcore.identity;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

  Optional<AppUser> findByUsernameNormalized(String usernameNormalized);

  /** 按 id 顺序锁住本租户全部可用管理员行（固定顺序避免并发死锁），用于"最后一名管理员"守卫。 */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select u from AppUser u where u.role = tech.toolkit.tenantcore.identity.Role.TENANT_ADMIN"
          + " and u.status = tech.toolkit.tenantcore.identity.Status.ACTIVE order by u.id")
  List<AppUser> lockActiveAdmins();
}
