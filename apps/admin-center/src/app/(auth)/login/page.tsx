import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { LoginForm } from "@/components/auth/login-form";
import { getSession } from "@/lib/session/get-session";
import { DEFAULT_NEXT_PATH, safeNextPath } from "@/lib/session/next-path";

export const metadata: Metadata = { title: "登录" };

type Param = string | string[] | undefined;

/**
 * 登录页（匿名）。已登录则去 `/users`。
 * `next` 在这里先过一遍白名单再放进表单，登录动作还会再校验一次；`reason=session_ended` 表示刚被带到这里是因为会话结束。
 */
export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: Param; reason?: Param }> }) {
  if ((await getSession()).status === "authenticated") redirect(DEFAULT_NEXT_PATH);

  const { next, reason } = await searchParams;
  return (
    <LoginForm next={safeNextPath(next)} notice={reason === "session_ended" ? "会话已结束，请重新登录。" : undefined} />
  );
}
