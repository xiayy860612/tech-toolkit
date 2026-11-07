'use client';

import { MenuIcon } from 'lucide-react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState, type ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet';

// Exactly three fixed items (spec: Story 3).
const ITEMS = [
  { href: '/tenants', label: '租户管理' },
  { href: '/modules', label: '模块管理' },
  { href: '/users', label: '用户管理' },
] as const;

function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();
  return (
    <nav aria-label="主导航">
      <ul className="flex flex-col gap-1">
        {ITEMS.map((item) => {
          const current = pathname === item.href || pathname.startsWith(`${item.href}/`);
          return (
            <li key={item.href}>
              <Link
                href={item.href}
                aria-current={current ? 'page' : undefined}
                onClick={onNavigate}
                className="flex h-11 items-center rounded-md px-3 text-sm hover:bg-accent aria-[current=page]:bg-primary aria-[current=page]:text-primary-foreground"
              >
                {item.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/** Logout stays a real form submit (POST /logout). */
function LogoutForm() {
  return (
    <form action="/logout" method="post">
      <Button type="submit" variant="outline" className="h-11 w-full">
        退出登录
      </Button>
    </form>
  );
}

/**
 * Console frame. From `md` up: left sidebar. Below `md`: top bar whose menu button opens a Sheet.
 * The sidebar is display:none below `md` and the Sheet content only exists while open, so
 * only one "主导航" is exposed at a time.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="min-h-screen md:flex">
      <aside className="hidden w-56 shrink-0 flex-col justify-between gap-6 border-r p-4 md:sticky md:top-0 md:flex md:h-screen">
        <NavLinks />
        <LogoutForm />
      </aside>
      <div className="min-w-0 flex-1">
        <header className="flex h-14 items-center border-b px-2 md:hidden">
          <Sheet open={open} onOpenChange={setOpen}>
            <SheetTrigger asChild>
              <Button variant="ghost" size="icon" className="size-11" aria-label="打开菜单">
                <MenuIcon />
              </Button>
            </SheetTrigger>
            <SheetContent side="left" showCloseButton={false} className="gap-6 p-4">
              <SheetHeader className="p-0">
                <SheetTitle>菜单</SheetTitle>
                <SheetDescription className="sr-only">选择要前往的页面或退出登录</SheetDescription>
              </SheetHeader>
              <NavLinks onNavigate={() => setOpen(false)} />
              <div className="mt-auto flex flex-col gap-2">
                <LogoutForm />
                <SheetClose asChild>
                  <Button variant="ghost" className="h-11">
                    关闭
                  </Button>
                </SheetClose>
              </div>
            </SheetContent>
          </Sheet>
        </header>
        <main className="p-4 md:p-6">{children}</main>
      </div>
    </div>
  );
}
