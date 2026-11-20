"use client";

import {
  ChevronDown,
  KeyRound,
  LogOut,
  Menu,
  PanelLeftClose,
  PanelLeftOpen,
  ShieldCheck,
  Users,
  X,
} from "lucide-react";
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
        <span className="hidden text-sm font-medium sm:inline">{name}</span>
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

const COLLAPSE_KEY = "sidebar-collapsed";

/** 侧栏里的导航项；{@code collapsed} 时只显示图标（文字仅对读屏可见，并带 title）。 */
function NavItems({
  variant,
  pathname,
  collapsed,
  onNavigate,
}: {
  variant: Variant;
  pathname: string;
  collapsed: boolean;
  onNavigate?: () => void;
}) {
  return (
    <>
      {NAV.map(({ href, label, icon: Icon }) => {
        const active = pathname === href || pathname.startsWith(`${href}/`);
        const cls = cn(
          "flex min-h-11 items-center gap-3 rounded-control px-3 text-sm",
          collapsed && "justify-center",
          active ? "bg-accent text-white" : "text-sidebar-text hover:bg-white/5",
        );
        const content = (
          <>
            <Icon className="size-4 shrink-0" aria-hidden="true" />
            <span className={collapsed ? "sr-only" : undefined}>{label}</span>
          </>
        );
        return variant === "locked" ? (
          <span
            key={href}
            aria-disabled="true"
            className={cn(cls, "cursor-not-allowed opacity-40")}
          >
            {content}
          </span>
        ) : (
          <Link
            key={href}
            href={href}
            title={collapsed ? label : undefined}
            aria-current={active ? "page" : undefined}
            onClick={onNavigate}
            className={cls}
          >
            {content}
          </Link>
        );
      })}
    </>
  );
}

/**
 * 登录后统一外壳：宽屏为可收起的侧边栏（232px / 64px 仅图标，选择记在 localStorage）+ 顶栏；
 * 窄屏（< lg）侧栏隐藏，顶栏按钮打开抽屉式侧栏（FR-031）。
 */
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
  const [collapsed, setCollapsed] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const hasSidebar = variant !== "minimal";

  useEffect(() => {
    try {
      setCollapsed(window.localStorage.getItem(COLLAPSE_KEY) === "1");
    } catch {
      // 存储不可用时保持默认展开
    }
  }, []);

  // 切换页面后关闭抽屉
  useEffect(() => setDrawerOpen(false), [pathname]);

  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setDrawerOpen(false);
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [drawerOpen]);

  function toggleCollapsed() {
    const next = !collapsed;
    setCollapsed(next);
    try {
      window.localStorage.setItem(COLLAPSE_KEY, next ? "1" : "0");
    } catch {
      // 写入失败不影响本次切换
    }
  }

  const iconButton =
    "inline-flex size-10 shrink-0 items-center justify-center rounded-control text-text-muted hover:bg-table-head";

  return (
    <div className="flex min-h-screen">
      {hasSidebar && (
        <aside
          className={cn(
            "hidden shrink-0 flex-col bg-sidebar lg:flex",
            collapsed ? "w-16" : "w-sidebar",
          )}
        >
          <div
            className={cn(
              "flex h-16 items-center text-base font-semibold text-white",
              collapsed ? "justify-center px-0" : "px-6",
            )}
          >
            {collapsed ? <span aria-hidden="true">租</span> : "租户管理中心"}
          </div>
          <nav aria-label="主导航" className="flex flex-col gap-1 px-3">
            <NavItems variant={variant} pathname={pathname} collapsed={collapsed} />
          </nav>
        </aside>
      )}

      {hasSidebar && drawerOpen && (
        <div className="fixed inset-0 z-30 flex lg:hidden">
          <div
            data-testid="drawer-backdrop"
            aria-hidden="true"
            className="drawer-backdrop absolute inset-0 bg-black/40"
            onClick={() => setDrawerOpen(false)}
          />
          <div className="drawer-panel relative flex h-full w-72 max-w-[85vw] flex-col bg-sidebar shadow-xl">
            <div className="flex h-16 items-center justify-between px-6 text-base font-semibold text-white">
              租户管理中心
              <button
                type="button"
                aria-label="关闭菜单"
                className="-mr-3 inline-flex size-10 items-center justify-center rounded-control text-sidebar-text hover:bg-white/5"
                onClick={() => setDrawerOpen(false)}
              >
                <X className="size-5" aria-hidden="true" />
              </button>
            </div>
            <nav aria-label="移动端导航" className="flex flex-col gap-1 px-3">
              <NavItems
                variant={variant}
                pathname={pathname}
                collapsed={false}
                onNavigate={() => setDrawerOpen(false)}
              />
            </nav>
          </div>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-16 items-center justify-between gap-3 border-b border-border bg-surface px-4 sm:px-8">
          <div className="flex min-w-0 items-center gap-2">
            {hasSidebar && (
              <>
                <button
                  type="button"
                  aria-label="打开菜单"
                  aria-expanded={drawerOpen}
                  className={cn(iconButton, "lg:hidden")}
                  onClick={() => setDrawerOpen(true)}
                >
                  <Menu className="size-5" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  aria-label={collapsed ? "展开侧边栏" : "收起侧边栏"}
                  aria-expanded={!collapsed}
                  className={cn(iconButton, "hidden lg:inline-flex")}
                  onClick={toggleCollapsed}
                >
                  {collapsed ? (
                    <PanelLeftOpen className="size-5" aria-hidden="true" />
                  ) : (
                    <PanelLeftClose className="size-5" aria-hidden="true" />
                  )}
                </button>
              </>
            )}
            <nav aria-label="面包屑" className="min-w-0 truncate text-sm text-text-muted">
              {crumbs.map((c, i) => (
                <span key={c}>
                  {i > 0 && <span className="mx-2">/</span>}
                  <span className={i === crumbs.length - 1 ? "text-text" : undefined}>{c}</span>
                </span>
              ))}
            </nav>
          </div>
          <UserMenu name={me.displayName} variant={variant} />
        </header>
        <main className="min-w-0 flex-1 px-4 py-6 sm:px-8">{children}</main>
      </div>
    </div>
  );
}
