'use client';

import { ChevronDownIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

export interface CurrentUser {
  displayName: string;
  accountUsername: string;
  tenantSlug: string;
}

/** The only place that offers 退出登录 (a real form submit: POST /logout). */
export function UserMenu({ user }: { user: CurrentUser }) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="h-11 max-w-48 gap-1 px-3">
          <span className="truncate">{user.displayName}</span>
          <ChevronDownIcon aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuLabel className="flex flex-col gap-0.5 font-normal">
          <span className="text-sm font-medium">{user.displayName}</span>
          <span className="text-xs text-muted-foreground">{user.accountUsername}</span>
          <span className="text-xs text-muted-foreground">{user.tenantSlug}</span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <form action="/logout" method="post">
          <DropdownMenuItem asChild className="h-11 cursor-pointer">
            <button type="submit" className="w-full">
              退出登录
            </button>
          </DropdownMenuItem>
        </form>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
