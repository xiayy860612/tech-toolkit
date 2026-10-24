"use server";

import "server-only";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getConfig } from "@/lib/config";
import { isSameOriginRequest } from "@/lib/security/origin-check";
import { getClientIp } from "@/lib/session/client-ip";
import { getSession } from "@/lib/session/get-session";
import { changePassword as uamChangePassword } from "@/lib/uam/client";
import { UamError, uamErrorMessage } from "@/lib/uam/errors";
import {
  CURRENT_PASSWORD_INCORRECT,
  mapUamPasswordFieldErrors,
  NEW_PASSWORD_RULE,
  parseChangePasswordForm,
  SAME_AS_CURRENT,
  type ChangePasswordFormState,
} from "@/lib/validation/password";

const PASSWORD_PATH = "/account/password";
const UNAVAILABLE = "服务暂不可用，请稍后重试";
/** 无法确认会话（UAM 暂不可用）：与 UAM 不可用同一文案「操作未生效」。 */
const UNVERIFIED = new UamError("unavailable", "无法确认会话");

/**
 * UAM 失败 → 表单状态（contracts/web-routes.md §5）。
 * - `current_password_incorrect` → 当前密码字段旁；`weak_password` / `same_as_current` / `validation_failed` → 新密码等字段旁；
 * - `throttled`：「尝试过于频繁」（带 Retry-After 秒数）；
 * - 其余（不可用、密钥配置错误、未知问题码）：「操作未生效」，绝不显示成功。
 * 返回值不含任何密码。
 */
function failureState(error: UamError): ChangePasswordFormState {
  if (error.kind === "problem") {
    switch (error.code) {
      case "current_password_incorrect":
        return { fieldErrors: { currentPassword: CURRENT_PASSWORD_INCORRECT } };
      case "weak_password":
        return { fieldErrors: { newPassword: NEW_PASSWORD_RULE } };
      case "same_as_current":
        return { fieldErrors: { newPassword: SAME_AS_CURRENT } };
      case "validation_failed": {
        const fieldErrors = mapUamPasswordFieldErrors(error.fieldErrors ?? []);
        return Object.keys(fieldErrors).length > 0 ? { fieldErrors } : { formError: uamErrorMessage(error, "write") };
      }
    }
  }
  return { formError: uamErrorMessage(error, "write") };
}

/**
 * 修改密码 Server Action（FR-026、FR-027）：同源校验 → 会话三态 → 输入校验 → 取终端来源地址 →
 * 调 UAM `changePassword`（操作人头）。
 * - 被改的账号**只**取自会话里操作人自己的账号，不读任何表单字段或 URL；
 * - 成功：返回 `{ success: true }`，会话与 cookie 不动；
 * - 返回值**不含任何密码**，确认项只在这里校验、不发给 UAM。
 */
export async function changePassword(_previous: ChangePasswordFormState, formData: FormData): Promise<ChangePasswordFormState> {
  const requestHeaders = await headers();
  if (!isSameOriginRequest("POST", requestHeaders)) {
    return { formError: "请求来源校验失败，请刷新页面后重试" };
  }

  const state = await getSession();
  if (state.status === "anonymous") {
    // 会话已结束：经 /session/expire 清掉失效 cookie，登录后回到改密页
    const next = `?next=${encodeURIComponent(PASSWORD_PATH)}`;
    redirect(`${state.hadCookie ? "/session/expire" : "/login"}${next}`);
  }
  if (state.status === "unverified") return { formError: uamErrorMessage(UNVERIFIED, "write") };

  const parsed = parseChangePasswordForm(formData);
  if (!parsed.success) return { fieldErrors: parsed.fieldErrors };

  // UAM 契约要求 clientIp（当前密码连续错误限制的来源维度）。取不到就不改：既不编造地址，也不让所有人共用一个限流桶
  const clientIp = getClientIp(requestHeaders, getConfig().trustedProxyHops);
  if (!clientIp) {
    console.warn("[change-password] 无法确定终端来源地址，已拒绝修改密码；请检查 TRUSTED_PROXY_HOPS 与反向代理的 X-Forwarded-For 设置");
    return { formError: UNAVAILABLE };
  }

  const { user, account, sessionRef } = state.session;
  try {
    await uamChangePassword(account.id, { ...parsed.data, clientIp }, { userId: user.id, accountId: account.id, sessionRef });
  } catch (error) {
    if (error instanceof UamError) return failureState(error);
    throw error; // 非 UAM 错误是程序问题，不要伪装成失败提示
  }
  return { success: true };
}
