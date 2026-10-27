import "server-only";
import { cache } from "react";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { UamError, type ProblemReason } from "@/lib/uam/errors";
import { verifySession, type Operator, type VerifyResponse } from "@/lib/uam/client";
import { readSessionId } from "./cookie";
import { isSafeNextPath } from "./next-path";

/**
 * 会话三态（contracts/web-routes.md §3）：
 * - `authenticated`：`verify` 返回 200；
 * - `anonymous`：没有 cookie，或 UAM 明确 401 `session_invalid`（`hadCookie` 表示带过 cookie，需要经 `/session/expire` 清掉；
 *   `reason: "terminated"` 表示该会话刚被管理员强制下线——UAM 只在这一种情形下给出，登录页据此显示专门的提示）；
 * - `unverified`：UAM 超时、5xx、连接失败，或 UAM 给出了其它非会话结论——保留 cookie，提示服务暂不可用。
 *
 * 会话数据只存在于单次请求的服务端内存：不写 cookie、不传给浏览器端组件、不进日志。
 */
export type SessionState =
  | { status: "authenticated"; session: VerifyResponse }
  | { status: "anonymous"; hadCookie: boolean; reason?: ProblemReason }
  | { status: "unverified" };

async function loadSession(): Promise<SessionState> {
  const sessionId = await readSessionId();
  if (!sessionId) return { status: "anonymous", hadCookie: false };
  try {
    return { status: "authenticated", session: await verifySession(sessionId) };
  } catch (error) {
    if (error instanceof UamError) {
      if (error.kind === "problem" && error.code === "session_invalid") {
        return error.reason ? { status: "anonymous", hadCookie: true, reason: error.reason } : { status: "anonymous", hadCookie: true };
      }
      return { status: "unverified" };
    }
    throw error; // 非 UAM 错误是程序问题（如配置缺失），不要伪装成 unverified
  }
}

/** 同一请求内去重（React `cache`）：布局与页面各调一次也只 `verify` 一次。 */
export const getSession = cache(loadSession);

/** 当前请求的操作人（用于 `X-Operator-*` 请求头）；未登录或无法确认会话时为 `null`（外壳会按三态处理）。 */
export async function getOperator(): Promise<Operator | null> {
  const state = await getSession();
  if (state.status !== "authenticated") return null;
  const { user, account, sessionRef } = state.session;
  return { userId: user.id, accountId: account.id, sessionRef };
}

/**
 * 未登录时的跳转目标：带过 cookie 先去 `/session/expire` 清掉失效 cookie，否则直接到 `/login`；
 * `next` 取 middleware 写的 `x-next-path`——它对每个请求（含 RSC 局部渲染请求）都会被重新计算，
 * 不受 Next 局部渲染跳过布局的影响，所以在这里和 `(shell)/layout.tsx` 里都可以直接用它。
 * 两处共用同一份逻辑，避免重复。
 */
export async function anonymousRedirectTarget(hadCookie: boolean): Promise<string> {
  const original = (await headers()).get("x-next-path");
  const query = isSafeNextPath(original) ? `?next=${encodeURIComponent(original)}` : "";
  return `${hadCookie ? "/session/expire" : "/login"}${query}`;
}

/**
 * 页面级会话守卫（US13 / RSC 局部渲染绕过）：Next.js 的局部渲染会根据客户端带来的
 * `Next-Router-State-Tree` 跳过没有变化的祖先段，`(shell)/layout.tsx` 可能完全不被调用，
 * 只渲染目标页面本身——所以每个受保护页面必须在读取任何数据（调用 UAM）之前，第一步调用本函数：
 * - authenticated：返回操作人，页面据此调用 UAM（带 `X-Operator-*` 请求头）；
 * - anonymous：与外壳布局相同的跳转（不调用 UAM）；
 * - unverified：返回 `null`。页面必须据此直接返回、不渲染任何内容、不调用 UAM——
 *   即使外壳布局本身被跳过，也不能让数据进入这次渲染的 flight 响应
 *   （`AppShell` 只在浏览器里隐藏子节点，服务端早已把它们序列化进响应）。
 */
export async function requireOperator(): Promise<Operator | null> {
  const state = await getSession();
  if (state.status === "unverified") return null;
  if (state.status === "anonymous") redirect(await anonymousRedirectTarget(state.hadCookie));
  const { user, account, sessionRef } = state.session;
  return { userId: user.id, accountId: account.id, sessionRef };
}
