import type { Me } from "@/lib/api/types";

export type ShellVariant = "full" | "minimal" | "locked";

export interface GuardDecision {
  redirect: string | null;
  variant: ShellVariant;
}

/**
 * 登录后外壳的守卫。依据 `/auth/me`（数据库当前值）而不是会话里缓存的角色：
 * 1. 须改密：任何页面都先去 /change-password，外壳锁定其余导航；
 * 2. 非 TENANT_ADMIN：只能停留在 /forbidden 与 /change-password，外壳无导航；
 * 3. 管理员：完整外壳，不应停留在 /forbidden。
 */
export function decideGuard(me: Me, pathname: string): GuardDecision {
  if (me.mustChangePassword) {
    return {
      redirect: pathname === "/change-password" ? null : "/change-password",
      variant: "locked",
    };
  }
  if (me.role !== "TENANT_ADMIN") {
    const allowed = pathname === "/forbidden" || pathname === "/change-password";
    return { redirect: allowed ? null : "/forbidden", variant: "minimal" };
  }
  return { redirect: pathname === "/forbidden" ? "/users" : null, variant: "full" };
}
