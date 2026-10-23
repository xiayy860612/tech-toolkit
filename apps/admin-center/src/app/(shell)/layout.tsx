import type { ReactNode } from "react";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { AppShell } from "@/components/shell/app-shell";
import { getSession } from "@/lib/session/get-session";

/**
 * 带外壳的路由组：读会话三态。
 * - anonymous 且带过 cookie：经 `/session/expire` 清掉失效 cookie（渲染期间不能写 cookie）；没带过则直接去登录页；
 * - unverified：保留登录状态，外壳显示「服务暂不可用」；
 * - authenticated：只把显示名传给浏览器端外壳，会话数据留在服务端。
 */
export default async function ShellLayout({ children }: { children: ReactNode }) {
  const state = await getSession();
  if (state.status === "anonymous") redirect(state.hadCookie ? "/session/expire" : "/login");

  const sidebarState = (await cookies()).get("sidebar_state")?.value;
  return (
    <AppShell
      status={state.status}
      userName={state.status === "authenticated" ? state.session.user.name : undefined}
      defaultSidebarOpen={sidebarState !== "false"}
    >
      {children}
    </AppShell>
  );
}
