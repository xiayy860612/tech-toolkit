'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

// Exactly three fixed items (spec: Story 3).
const ITEMS = [
  { href: '/tenants', label: '租户管理' },
  { href: '/modules', label: '模块管理' },
  { href: '/users', label: '用户管理' },
] as const;

export function Sidebar() {
  const pathname = usePathname();
  return (
    <nav aria-label="主导航" className="sidebar">
      <ul>
        {ITEMS.map((item) => {
          const current = pathname === item.href || pathname.startsWith(`${item.href}/`);
          return (
            <li key={item.href}>
              <Link href={item.href} aria-current={current ? 'page' : undefined}>
                {item.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
