"use client";

import { useState, type ReactNode } from "react";
import { Alert } from "@/components/ui/alert";
import { Card, CardContent } from "@/components/ui/card";
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar";
import { useMediaQuery } from "@/hooks/use-media-query";
import { AppHeader } from "./app-header";
import { AppSidebar } from "./app-sidebar";

export interface AppShellProps {
  /** 会话三态。只传状态与显示名：会话数据不进入浏览器端组件。 */
  status: "authenticated" | "anonymous" | "unverified";
  userName?: string;
  /** 服务端从非敏感 UI cookie `sidebar_state` 读出的展开偏好。 */
  defaultSidebarOpen?: boolean;
  children: ReactNode;
}

/**
 * 受保护页面的共用外壳：顶栏 + 侧边栏 + 内容区。
 * - 已登录：顶栏（品牌、侧边栏开关、用户菜单）+ 侧边栏；
 * - 匿名 / unverified：只有顶栏；unverified 显示「服务暂不可用」横幅并说明登录状态已保留，不渲染需要会话的内容。
 * 侧边栏：桌面 ≥ 1024 按偏好展开；平板 768–1023 固定为图标条；手机 < 768 为抽屉。
 */
export function AppShell({ status, userName, defaultSidebarOpen = true, children }: AppShellProps) {
  const [preferOpen, setPreferOpen] = useState(defaultSidebarOpen);
  const isDesktop = useMediaQuery("(min-width: 1024px)");
  const authenticated = status === "authenticated";

  return (
    <SidebarProvider open={isDesktop && preferOpen} onOpenChange={setPreferOpen} className="flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-background focus:px-4 focus:py-3 focus:shadow"
      >
        跳到主要内容
      </a>
      <AppHeader authenticated={authenticated} userName={userName} />
      <div className="flex min-w-0 flex-1">
        {authenticated && <AppSidebar />}
        <SidebarInset id="main" className="min-w-0 p-4 md:p-6">
          {status === "unverified" ? (
            <>
              <Alert role="status" className="mb-4 border-0 bg-warning text-warning-foreground">
                <span className="col-start-2">
                  <strong>服务暂不可用，请稍后重试。</strong>
                  你的登录状态已保留，服务恢复后无需重新登录。写操作此时会提示「操作未生效」。
                </span>
              </Alert>
              <Card>
                <CardContent className="py-10 text-center text-muted-foreground">
                  <strong className="mb-1 block text-base text-foreground">服务暂不可用</strong>
                  暂时无法获取数据，请稍后刷新页面。
                </CardContent>
              </Card>
            </>
          ) : (
            children
          )}
        </SidebarInset>
      </div>
    </SidebarProvider>
  );
}
