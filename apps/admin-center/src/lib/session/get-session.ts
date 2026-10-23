import "server-only";
import { cache } from "react";
import { UamError } from "@/lib/uam/errors";
import { verifySession, type Operator, type VerifyResponse } from "@/lib/uam/client";
import { readSessionId } from "./cookie";

/**
 * 会话三态（contracts/web-routes.md §3）：
 * - `authenticated`：`verify` 返回 200；
 * - `anonymous`：没有 cookie，或 UAM 明确 401 `session_invalid`（`hadCookie` 表示带过 cookie，需要经 `/session/expire` 清掉）；
 * - `unverified`：UAM 超时、5xx、连接失败，或拒绝了 Admin Center 本身（服务密钥配置错误）——保留 cookie，提示服务暂不可用。
 *
 * 会话数据只存在于单次请求的服务端内存：不写 cookie、不传给浏览器端组件、不进日志。
 */
export type SessionState =
  | { status: "authenticated"; session: VerifyResponse }
  | { status: "anonymous"; hadCookie: boolean }
  | { status: "unverified" };

async function loadSession(): Promise<SessionState> {
  const sessionId = await readSessionId();
  if (!sessionId) return { status: "anonymous", hadCookie: false };
  try {
    return { status: "authenticated", session: await verifySession(sessionId) };
  } catch (error) {
    if (error instanceof UamError) {
      if (error.kind === "problem" && error.code === "session_invalid") return { status: "anonymous", hadCookie: true };
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
