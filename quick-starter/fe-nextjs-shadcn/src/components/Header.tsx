"use client";

import { ChangePasswordDialog } from "@/components/ChangePasswordDialog";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useAuth } from "@/contexts/AuthContext";
import { KeyRound, LogOut, Shield, User } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";

export function Header() {
  const { user, logout } = useAuth();
  const router = useRouter();
  const [passwordDialogOpen, setPasswordDialogOpen] = useState(false);

  return (
    <header className="border-b bg-background">
      <div className="flex h-16 items-center justify-between px-6">
        <div
          className="flex cursor-pointer items-center gap-2 hover:opacity-80"
          onClick={() => router.push("/")}
        >
          <h1 className="text-xl font-semibold">Quick Starter Demo</h1>
        </div>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" className="gap-2">
              <User className="size-4" />
              <span className="text-sm">{user?.username}</span>
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-48">
            <div className="flex items-center gap-2 px-2 py-1.5 text-sm text-muted-foreground">
              <User className="size-4" />
              <span>{user?.username}</span>
            </div>
            <DropdownMenuSeparator />
            {user?.role === "admin" && (
              <DropdownMenuItem
                onClick={() => router.push("/admin")}
                className="cursor-pointer"
              >
                <Shield className="size-4" />
                <span>Admin</span>
              </DropdownMenuItem>
            )}
            <DropdownMenuItem
              onClick={() => setPasswordDialogOpen(true)}
              className="cursor-pointer"
            >
              <KeyRound className="size-4" />
              <span>Change Password</span>
            </DropdownMenuItem>
            <DropdownMenuItem onClick={logout} className="cursor-pointer">
              <LogOut className="size-4" />
              <span>Logout</span>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <ChangePasswordDialog
        open={passwordDialogOpen}
        onOpenChange={setPasswordDialogOpen}
      />
    </header>
  );
}
