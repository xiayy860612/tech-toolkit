"use client";

import Link from "next/link";
import { ChevronDownIcon, KeyRoundIcon, LogOutIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

/** 顶栏用户菜单：修改密码、退出登录。退出是 POST /logout（写操作，服务端做同源校验）。 */
export function UserMenu({ userName }: { userName: string }) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="h-11 gap-2 px-2" aria-haspopup="menu">
          <span
            aria-hidden="true"
            className="grid size-8 flex-none place-items-center rounded-full bg-primary font-bold text-primary-foreground"
          >
            {userName.charAt(0) || "?"}
          </span>
          <span className="hidden max-w-40 truncate md:inline">{userName}</span>
          <ChevronDownIcon aria-hidden="true" className="hidden md:block" />
          <span className="sr-only md:hidden">{userName}</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuLabel className="break-all">{userName}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild className="min-h-11 cursor-pointer">
          <Link href="/account/password">
            <KeyRoundIcon aria-hidden="true" />
            修改密码
          </Link>
        </DropdownMenuItem>
        <form method="post" action="/logout">
          <DropdownMenuItem asChild className="min-h-11 w-full cursor-pointer">
            <button type="submit">
              <LogOutIcon aria-hidden="true" />
              退出登录
            </button>
          </DropdownMenuItem>
        </form>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
