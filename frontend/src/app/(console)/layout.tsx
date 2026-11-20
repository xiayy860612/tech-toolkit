import { headers } from "next/headers";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { AppShell } from "@/components/AppShell";
import { loginUrlFor } from "@/lib/navigation";
import { decideGuard } from "@/server/guard";
import { getCurrentSession } from "@/server/session/current";

export const dynamic = "force-dynamic";

/** 服务端会话守卫：无会话 → /login；须改密 / 非管理员 → 对应页面；角色以 /auth/me 为准。 */
export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  const requestHeaders = await headers();
  const pathname = requestHeaders.get("x-pathname") ?? "/";

  const me = await getCurrentSession(requestHeaders);
  if (!me) redirect(loginUrlFor(pathname));

  const guard = decideGuard(me, pathname);
  if (guard.redirect) redirect(guard.redirect);

  return (
    <AppShell me={me} variant={guard.variant}>
      {children}
    </AppShell>
  );
}
