"use server";

import "server-only";
import { revalidatePath } from "next/cache";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { isSameOriginRequest } from "@/lib/security/origin-check";
import { getSession } from "@/lib/session/get-session";
import { terminateSession as uamTerminateSession } from "@/lib/uam/client";
import { UamError, uamErrorMessage } from "@/lib/uam/errors";
import { parseSessionRef, parseUserId } from "@/lib/validation/users-query";

/**
 * 强制下线的结果：`ok` 才是「已下线」；`ended` 表示会话本来就已结束（友好提示，界面不当作错误）；
 * `message` 是给用户看的文案，不含任何敏感信息。
 */
export type TerminateResult = { ok: true; message: string } | { ok: false; message: string; ended?: true };

/** 无法确认会话（UAM 暂不可用）：与 UAM 不可用同一文案「操作未生效」。 */
const UNVERIFIED = new UamError("unavailable", "无法确认会话");

/**
 * 强制下线某用户的某个会话 Server Action（FR-030）：同源校验 → 标识格式 → 会话三态 → 调 UAM（操作人头）→ 刷新页面。
 * - 成功：刷新该用户的会话页与在线用户列表（该用户可能已无会话）；
 * - 自己的当前会话由 UAM 裁决（409 `cannot_terminate_own_session`），显示 §5 文案，状态没变所以不刷新；
 * - 会话已结束（404 `session_not_found`）：`ended`，并刷新为最新状态；
 * - 其余（不可用、密钥配置错误、未知问题码）：「操作未生效」，**绝不显示成功**。
 */
export async function terminateSession(userId: string, sessionRef: string): Promise<TerminateResult> {
  if (!isSameOriginRequest("POST", await headers())) {
    return { ok: false, message: "请求来源校验失败，请刷新页面后重试" };
  }
  const uid = parseUserId(userId);
  const ref = parseSessionRef(sessionRef);
  if (!uid || !ref) return { ok: false, message: "会话不存在，请刷新页面后重试" };

  const path = `/sessions/${uid}`;
  const state = await getSession();
  if (state.status === "anonymous") {
    // 会话已结束：经 /session/expire 清掉失效 cookie，登录后回到这个用户的会话页
    const next = `?next=${encodeURIComponent(path)}`;
    redirect(`${state.hadCookie ? "/session/expire" : "/login"}${next}`);
  }
  if (state.status === "unverified") return { ok: false, message: uamErrorMessage(UNVERIFIED, "write") };

  const { user, account, sessionRef: operatorRef } = state.session;
  const refresh = () => {
    revalidatePath(path);
    revalidatePath("/sessions");
  };
  try {
    await uamTerminateSession(ref, { userId: user.id, accountId: account.id, sessionRef: operatorRef });
    refresh();
    return { ok: true, message: "会话已强制下线。" };
  } catch (error) {
    if (!(error instanceof UamError)) throw error; // 非 UAM 错误是程序问题，不要伪装成失败提示
    if (error.kind === "misconfigured") {
      // UAM 拒绝了 Admin Center 本身（通常是服务密钥或客户端权限配置错误）：用户只看到「操作未生效」，运维看到告警
      console.error(`[session-terminate] UAM 拒绝了 Admin Center 的服务调用（HTTP ${error.status}），请检查 UAM_SERVICE_KEY 与客户端权限`);
    }
    const message = uamErrorMessage(error, "write");
    if (error.kind === "problem" && error.code === "session_not_found") {
      refresh();
      return { ok: false, ended: true, message };
    }
    return { ok: false, message };
  }
}
