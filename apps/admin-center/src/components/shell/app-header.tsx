"use client";

import { useEffect, useRef } from "react";
import { MenuIcon, PanelLeftCloseIcon, PanelLeftOpenIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useSidebar } from "@/components/ui/sidebar";
import { useMediaQuery } from "@/hooks/use-media-query";
import { UserMenu } from "./user-menu";

/**
 * 侧边栏开关：手机是顶栏菜单按钮（打开抽屉），桌面是收起 / 展开，平板（图标条固定）不显示。
 */
function SidebarToggle() {
  const { isMobile, open, openMobile, toggleSidebar } = useSidebar();
  const isDesktop = useMediaQuery("(min-width: 1024px)");
  const buttonRef = useRef<HTMLButtonElement>(null);
  const wasOpenMobile = useRef(false);

  // 抽屉没有挂在 Dialog.Trigger 上（受控打开），Radix 不会把焦点还给触发按钮，这里手动归还（FR-036）
  useEffect(() => {
    if (isMobile && wasOpenMobile.current && !openMobile) buttonRef.current?.focus();
    wasOpenMobile.current = openMobile;
  }, [isMobile, openMobile]);

  if (!isMobile && !isDesktop) return null; // 平板：图标条固定，不提供开关
  const label = isMobile ? "打开导航菜单" : open ? "收起侧边栏" : "展开侧边栏";
  const Icon = isMobile ? MenuIcon : open ? PanelLeftCloseIcon : PanelLeftOpenIcon;
  return (
    <Button
      ref={buttonRef}
      variant="ghost"
      size="icon"
      className="size-11"
      aria-label={label}
      aria-expanded={isMobile ? openMobile : open}
      onClick={toggleSidebar}
    >
      <Icon aria-hidden="true" />
    </Button>
  );
}

export function AppHeader({ authenticated, userName }: { authenticated: boolean; userName?: string }) {
  return (
    <header className="sticky top-0 z-20 flex h-14 flex-none items-center gap-2 border-b bg-card px-3 sm:px-4">
      {authenticated && <SidebarToggle />}
      <span className="text-base font-bold whitespace-nowrap">Admin Center</span>
      <span className="flex-1" />
      {authenticated && <UserMenu userName={userName ?? ""} />}
    </header>
  );
}
