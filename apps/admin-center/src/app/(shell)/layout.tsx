import type { ReactNode } from "react";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { AppShell } from "@/components/shell/app-shell";
import { anonymousRedirectTarget, getSession } from "@/lib/session/get-session";

/**
 * 带外壳的路由组：读会话三态。
 * - anonymous：带 `next=<原路径>` 去登录页（FR-012；跳转目标与 `requireOperator()` 共用同一份逻辑）；
 * - unverified：保留登录状态，外壳显示「服务暂不可用」；
 * - authenticated：只把显示名传给浏览器端外壳，会话数据留在服务端。
 *
 * 注意：Next.js 的局部渲染可能完全跳过本布局（见 `requireOperator()` 的说明），所以这里的会话校验
 * 不是唯一的安全边界——每个受保护页面都必须自己先调用 `requireOperator()`，这里只是未被跳过时的
 * 第一道、也是用户体验最友好的一道（能整页跳转，而不是渲染空内容）。
 */
export default async function ShellLayout({ children }: { children: ReactNode }) {
  const state = await getSession();
  if (state.status === "anonymous") {
    redirect(await anonymousRedirectTarget(state.hadCookie));
  }

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
