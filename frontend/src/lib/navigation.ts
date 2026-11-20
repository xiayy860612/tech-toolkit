/** 登录页地址；保留原目标页（Edge Case：会话过期后回到原页面）。 */
export function loginUrlFor(pathname: string, search = ""): string {
  if (pathname === "/" || pathname.startsWith("/login")) return "/login";
  return `/login?next=${encodeURIComponent(pathname + search)}`;
}

/** 仅接受站内相对路径，防止开放重定向；其余回退到默认落地页。 */
export function safeNext(next: unknown, fallback = "/users"): string {
  if (typeof next !== "string") return fallback;
  if (!next.startsWith("/") || next.startsWith("//") || next.includes("\\")) return fallback;
  if (next.startsWith("/login")) return fallback;
  return next;
}

/**
 * 整页跳转。认证状态变化（登录、改密）后必须用它，而不是 router.replace：
 * 同一 (console) 布局下的软导航不会重新执行布局里的服务端守卫（须改密 / 角色 / 会话），
 * 整页跳转才能让守卫按最新状态决定去向。
 */
export function hardNavigate(url: string): void {
  window.location.assign(url);
}
