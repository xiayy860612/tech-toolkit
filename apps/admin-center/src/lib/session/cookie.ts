import "server-only";
import { cookies } from "next/headers";
import type { NextResponse } from "next/server";
import { getConfig } from "@/lib/config";

/**
 * 会话 cookie（contracts/web-routes.md §2）：`HttpOnly; Secure; SameSite=Strict; Path=/`，不设 `Domain`
 * （`__Host-` 前缀要求如此）；内容只有机密的会话标识。
 */
export function sessionCookieOptions(maxAge: number) {
  return { httpOnly: true, secure: true, sameSite: "strict" as const, path: "/", maxAge };
}

export async function readSessionId(): Promise<string | undefined> {
  const cookie = (await cookies()).get(getConfig().sessionCookieName);
  return cookie?.value || undefined;
}

/** 登录成功时写入；`maxAgeSeconds` 取 UAM 返回的剩余绝对寿命。 */
export async function setSessionCookie(sessionId: string, maxAgeSeconds: number): Promise<void> {
  (await cookies()).set(getConfig().sessionCookieName, sessionId, sessionCookieOptions(maxAgeSeconds));
}

/**
 * 清除会话 cookie：用同样的属性写一个立即过期的空值（`__Host-` cookie 必须属性一致才会被覆盖）。
 * 在路由处理器里传入响应对象，在 Server Action 里省略。
 */
export async function clearSessionCookie(response?: NextResponse): Promise<void> {
  const name = getConfig().sessionCookieName;
  if (response) response.cookies.set(name, "", sessionCookieOptions(0));
  else (await cookies()).set(name, "", sessionCookieOptions(0));
}
