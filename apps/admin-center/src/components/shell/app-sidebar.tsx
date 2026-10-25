"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from "@/components/ui/sidebar";
import { isNavActive, NAV_ITEMS } from "./nav-config";

/**
 * 侧边栏：桌面展开、平板图标条（`AppShell` 控制）、手机抽屉（shadcn Sidebar 在手机上渲染为 Sheet，
 * 自带焦点限制、Esc 关闭与焦点归还）。导航项触控高度 48px，图标条下为 44px 方块。
 */
export function AppSidebar() {
  const pathname = usePathname();
  const { isMobile, setOpenMobile } = useSidebar();

  return (
    <Sidebar collapsible="icon" className="top-14 h-[calc(100svh-3.5rem)]">
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupContent>
            <nav aria-label="主导航">
              <SidebarMenu>
                {NAV_ITEMS.map(({ href, label, icon: Icon }) => {
                  const active = isNavActive(pathname, href);
                  return (
                    <SidebarMenuItem key={href}>
                      <SidebarMenuButton
                        asChild
                        size="lg"
                        isActive={active}
                        // 抽屉里不挂 tooltip：它会成为 Esc 要先关的一层，导致 Esc 需要按两次
                        tooltip={isMobile ? undefined : label}
                        className="group-data-[collapsible=icon]:size-11! group-data-[collapsible=icon]:justify-center"
                      >
                        <Link
                          href={href}
                          aria-current={active ? "page" : undefined}
                          onClick={() => setOpenMobile(false)}
                        >
                          <Icon aria-hidden="true" />
                          <span>{label}</span>
                        </Link>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  );
                })}
              </SidebarMenu>
            </nav>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
    </Sidebar>
  );
}
