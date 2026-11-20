package tech.toolkit.tenantcore.platform;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 当前线程的租户上下文。租户只来自已验签的内部 JWT（FR-025），由安全过滤器设置；事务开始时写入 {@code app.tenant_id}，驱动数据库 Row-Level
 * Security。
 */
public final class TenantContext {

  private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static Optional<Long> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static void set(long tenantId) {
    CURRENT.set(tenantId);
  }

  public static void clear() {
    CURRENT.remove();
  }

  public static <T> T callAs(long tenantId, Supplier<T> action) {
    Long previous = CURRENT.get();
    CURRENT.set(tenantId);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  public static void runAs(long tenantId, Runnable action) {
    callAs(
        tenantId,
        () -> {
          action.run();
          return null;
        });
  }
}
