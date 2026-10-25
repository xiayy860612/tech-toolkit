import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { LoginForm } from "@/components/auth/login-form";
import { getSession } from "@/lib/session/get-session";
import { DEFAULT_NEXT_PATH, safeNextPath } from "@/lib/session/next-path";

export const metadata: Metadata = { title: "登录" };

type Param = string | string[] | undefined;

/** `reason` 白名单 → 登录页提示；不在表里的取值不显示提示。 */
const NOTICES: Record<string, string> = {
  session_ended: "会话已结束，请重新登录。",
  session_terminated: "你的会话已被管理员强制下线",
};

function noticeFor(reason: Param): string | undefined {
  return typeof reason === "string" && Object.hasOwn(NOTICES, reason) ? NOTICES[reason] : undefined;
}

/**
 * 登录页（匿名）。已登录则去 `/users`。
 * `next` 在这里先过一遍白名单再放进表单，登录动作还会再校验一次；`reason` 表示刚被带到这里的原因：`session_ended` 会话结束（到期 / 退出 / 被取代 / 用户被停用），`session_terminated` 被管理员强制下线。
 */
export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: Param; reason?: Param }> }) {
  if ((await getSession()).status === "authenticated") redirect(DEFAULT_NEXT_PATH);

  const { next, reason } = await searchParams;
  return <LoginForm next={safeNextPath(next)} notice={noticeFor(reason)} />;
}
