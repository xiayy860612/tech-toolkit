import { MonitorSmartphoneIcon, UsersIcon, type LucideIcon } from "lucide-react";

export interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
}

/** 侧边栏导航：本期只有两个入口（spec 范围外不加别的）。 */
export const NAV_ITEMS: NavItem[] = [
  { href: "/users", label: "用户管理", icon: UsersIcon },
  { href: "/sessions", label: "会话管理", icon: MonitorSmartphoneIcon },
];

/** 当前路径是否落在该导航项下（含子路径，如 `/users/{id}`、`/users/new`）。 */
export function isNavActive(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}
