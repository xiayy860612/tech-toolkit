"use client";

import { ChevronDown, KeyRound, LogOut, ShieldCheck, Users } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { apiRequest } from "@/lib/api/client";
import { cn } from "@/lib/utils";

type Variant = "full" | "minimal" | "locked";

const NAV = [
  { href: "/users", label: "用户管理", icon: Users },
  { href: "/sessions", label: "会话管理", icon: ShieldCheck },
];

function breadcrumbFor(pathname: string): string[] {
  if (pathname.startsWith("/users")) {
    if (pathname === "/users") return ["用户管理"];
    if (pathname === "/users/new") return ["用户管理", "新建用户"];
    if (pathname.endsWith("/edit")) return ["用户管理", "编辑用户"];
    return ["用户管理", "用户详情"];
  }
  if (pathname.startsWith("/sessions")) return ["会话管理"];
  if (pathname.startsWith("/change-password")) return ["修改密码"];
  if (pathname.startsWith("/forbidden")) return ["无权访问"];
  return [];
}

function UserMenu({ name, variant }: { name: string; variant: Variant }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    const onDown = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", onDown);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("mousedown", onDown);
    };
  }, [open]);

  async function logout() {
    try {
      await apiRequest("/auth/logout", { method: "POST" });
    } catch {
      // 无论后端是否可达都回到登录页（BFF 已清除 Cookie）
    }
    router.replace("/login");
  }

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="flex min-h-11 items-center gap-2 rounded-control px-2 hover:bg-table-head"
      >
        <span
          aria-hidden="true"
          className="flex size-8 items-center justify-center rounded-full bg-accent text-sm font-medium text-white"
        >
          {name.slice(0, 1)}
        </span>
        <span className="text-sm font-medium">{name}</span>
        <ChevronDown className="size-4 text-text-muted" aria-hidden="true" />
      </button>
      {open && (
        <div
          role="menu"
          className="absolute right-0 z-20 mt-1 w-44 rounded-control border border-border bg-surface py-1 shadow-lg"
        >
          {variant !== "locked" && (
            <Link
              role="menuitem"
              href="/change-password"
              onClick={() => setOpen(false)}
              className="flex min-h-11 items-center gap-2 px-3 text-sm hover:bg-table-head"
            >
              <KeyRound className="size-4" aria-hidden="true" />
              修改密码
            </Link>
          )}
          <button
            type="button"
            role="menuitem"
            onClick={logout}
            className="flex min-h-11 w-full items-center gap-2 px-3 text-left text-sm hover:bg-table-head"
          >
            <LogOut className="size-4" aria-hidden="true" />
            退出登录
          </button>
        </div>
      )}
    </div>
  );
}

/** 登录后统一外壳：侧边栏（232px）+ 顶栏（面包屑、用户菜单）。 */
export function AppShell({
  me,
  variant,
  children,
}: {
  me: { displayName: string };
  variant: Variant;
  children: ReactNode;
}) {
  const pathname = usePathname();
  const crumbs = breadcrumbFor(pathname);

  return (
    <div className="flex min-h-screen">
      {variant !== "minimal" && (
        <aside className="hidden w-sidebar shrink-0 flex-col bg-sidebar md:flex">
          <div className="flex h-16 items-center px-6 text-base font-semibold text-white">
            租户管理中心
          </div>
          <nav aria-label="主导航" className="flex flex-col gap-1 px-3">
            {NAV.map(({ href, label, icon: Icon }) => {
              const active = pathname === href || pathname.startsWith(`${href}/`);
              const cls = cn(
                "flex min-h-11 items-center gap-3 rounded-control px-3 text-sm",
                active ? "bg-accent text-white" : "text-sidebar-text hover:bg-white/5",
              );
              return variant === "locked" ? (
                <span
                  key={href}
                  aria-disabled="true"
                  className={cn(cls, "cursor-not-allowed opacity-40")}
                >
                  <Icon className="size-4" aria-hidden="true" />
                  {label}
                </span>
              ) : (
                <Link
                  key={href}
                  href={href}
                  aria-current={active ? "page" : undefined}
                  className={cls}
                >
                  <Icon className="size-4" aria-hidden="true" />
                  {label}
                </Link>
              );
            })}
          </nav>
        </aside>
      )}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-16 items-center justify-between border-b border-border bg-surface px-8">
          <nav aria-label="面包屑" className="text-sm text-text-muted">
            {crumbs.map((c, i) => (
              <span key={c}>
                {i > 0 && <span className="mx-2">/</span>}
                <span className={i === crumbs.length - 1 ? "text-text" : undefined}>{c}</span>
              </span>
            ))}
          </nav>
          <UserMenu name={me.displayName} variant={variant} />
        </header>
        <main className="flex-1 px-8 py-6">{children}</main>
      </div>
    </div>
  );
}
