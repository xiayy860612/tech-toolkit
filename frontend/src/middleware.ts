import { NextResponse, type NextRequest } from "next/server";
import { loginUrlFor } from "@/lib/navigation";
import { getConfig } from "@/server/config";

/**
 * 仅体验层：没有会话 Cookie 时跳到 /login 并保留 next。真正的鉴权在后端（每个请求校验 sid）。
 * 同时把 pathname 透传给服务端布局，供其做改密 / 角色守卫。
 */
export function middleware(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const hasSession = Boolean(request.cookies.get(getConfig().cookieName)?.value);

  if (!hasSession && !pathname.startsWith("/login")) {
    return NextResponse.redirect(new URL(loginUrlFor(pathname, search), request.url));
  }

  const headers = new Headers(request.headers);
  headers.set("x-pathname", pathname);
  return NextResponse.next({ request: { headers } });
}

export const config = {
  matcher: ["/((?!api/|_next/|favicon\\.ico).*)"],
};
