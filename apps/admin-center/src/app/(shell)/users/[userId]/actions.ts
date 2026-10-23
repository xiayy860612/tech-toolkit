"use server";

import "server-only";
import { revalidatePath } from "next/cache";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { isSameOriginRequest } from "@/lib/security/origin-check";
import { getSession } from "@/lib/session/get-session";
import { disableUser as uamDisableUser, enableUser as uamEnableUser, type Operator, type UserDetail } from "@/lib/uam/client";
import { UamError, uamErrorMessage } from "@/lib/uam/errors";
import { parseUserId } from "@/lib/validation/users-query";

/** 停用 / 启用的结果：`ok` 才显示成功提示；`message` 是给用户看的文案（成功或原因），不含任何敏感信息。 */
export type UserStatusResult = { ok: true; message: string } | { ok: false; message: string };

/** 无法确认会话（UAM 暂不可用）：与 UAM 不可用同一文案「操作未生效」。 */
const UNVERIFIED = new UamError("unavailable", "无法确认会话");

type UamWrite = (userId: string, operator: Operator) => Promise<UserDetail>;

/**
 * 停用 / 启用共用的流程：同源校验 → 用户标识格式 → 会话三态 → 调 UAM（操作人头）→ 刷新详情页。
 * - 成功：刷新详情页（状态徽标、提示、入口随之更新），提示文案由 `success` 按 UAM 返回的用户生成；
 * - UAM 明确拒绝（`cannot_disable_*`、`user_not_found`）：显示 §5 文案；`user_not_found` 同时刷新页面，让它显示「用户不存在」；
 * - 其余（不可用、密钥配置错误、未知问题码）：「操作未生效」，**绝不显示成功**。
 */
async function changeStatus(userId: string, write: UamWrite, success: (user: UserDetail) => string): Promise<UserStatusResult> {
  if (!isSameOriginRequest("POST", await headers())) {
    return { ok: false, message: "请求来源校验失败，请刷新页面后重试" };
  }
  const id = parseUserId(userId);
  if (!id) return { ok: false, message: "用户不存在" };

  const path = `/users/${id}`;
  const state = await getSession();
  if (state.status === "anonymous") {
    // 会话已结束：经 /session/expire 清掉失效 cookie，登录后回到这个用户的详情页
    const next = `?next=${encodeURIComponent(path)}`;
    redirect(`${state.hadCookie ? "/session/expire" : "/login"}${next}`);
  }
  if (state.status === "unverified") return { ok: false, message: uamErrorMessage(UNVERIFIED, "write") };

  const { user, account, sessionRef } = state.session;
  try {
    const updated = await write(id, { userId: user.id, accountId: account.id, sessionRef });
    revalidatePath(path);
    return { ok: true, message: success(updated) };
  } catch (error) {
    if (!(error instanceof UamError)) throw error; // 非 UAM 错误是程序问题，不要伪装成失败提示
    if (error.kind === "misconfigured") {
      // UAM 拒绝了 Admin Center 本身（通常是服务密钥或客户端权限配置错误）：用户只看到「操作未生效」，运维看到告警
      console.error(`[user-status] UAM 拒绝了 Admin Center 的服务调用（HTTP ${error.status}），请检查 UAM_SERVICE_KEY 与客户端权限`);
    }
    if (error.kind === "problem" && error.code === "user_not_found") revalidatePath(path);
    return { ok: false, message: uamErrorMessage(error, "write") };
  }
}

/** 停用用户 Server Action（FR-024、FR-025）：其全部账号不能登录、全部会话立即结束；规则由 UAM 裁决（不能停用自己 / 最后一个正常用户）。 */
export async function disableUser(userId: string): Promise<UserStatusResult> {
  return changeStatus(userId, uamDisableUser, (user) => `用户「${user.name}」已停用，其会话已结束。`);
}

/** 启用用户 Server Action（FR-024）：恢复账号登录能力，不恢复已结束的会话。 */
export async function enableUser(userId: string): Promise<UserStatusResult> {
  return changeStatus(userId, uamEnableUser, (user) => `用户「${user.name}」已启用。`);
}
