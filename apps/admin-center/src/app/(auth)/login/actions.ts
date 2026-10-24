"use server";

import "server-only";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getConfig } from "@/lib/config";
import { isSameOriginRequest } from "@/lib/security/origin-check";
import { setSessionCookie } from "@/lib/session/cookie";
import { getClientIp } from "@/lib/session/client-ip";
import { safeNextPath } from "@/lib/session/next-path";
import { login as uamLogin } from "@/lib/uam/client";
import { fieldReasonMessage, UamError, uamErrorMessage } from "@/lib/uam/errors";
import { parseLoginForm, type LoginFieldErrors, type LoginFormState } from "@/lib/validation/login";

const UNAVAILABLE = "服务暂不可用，请稍后重试";

/** UAM 失败 → 表单状态。`invalid_credentials` 一律统一文案，不透露是账号名还是密码错。 */
function failureState(error: UamError, accountName: string): LoginFormState {
  if (error.kind === "problem" && error.code === "validation_failed") {
    const fieldErrors: LoginFieldErrors = {};
    for (const { field, reason } of error.fieldErrors ?? []) {
      if (field === "accountName" || field === "password") fieldErrors[field] = fieldReasonMessage(reason);
    }
    if (Object.keys(fieldErrors).length > 0) return { fieldErrors, accountName };
  }
  return { formError: uamErrorMessage(error, "read"), accountName };
}

/**
 * 登录 Server Action（contracts/web-routes.md §4）：同源校验 → 输入校验 → 取终端来源地址 →
 * 调 UAM `login` → 写会话 cookie → 按白名单跳转。
 * 返回值只含提示与账号名，**不含密码**；成功时不返回（`redirect` 抛出）。
 */
export async function login(_previous: LoginFormState, formData: FormData): Promise<LoginFormState> {
  const requestHeaders = await headers();
  if (!isSameOriginRequest("POST", requestHeaders)) {
    return { formError: "请求来源校验失败，请刷新页面后重试" };
  }

  const parsed = parseLoginForm(formData);
  if (!parsed.success) return { fieldErrors: parsed.fieldErrors, accountName: parsed.accountName };
  const { accountName, password } = parsed.data;

  // UAM 契约要求 clientIp（登录失败限制的来源维度）。取不到就不登录：既不编造地址，也不让所有人共用一个限流桶
  const clientIp = getClientIp(requestHeaders, getConfig().trustedProxyHops);
  if (!clientIp) {
    console.warn("[login] 无法确定终端来源地址，已拒绝登录；请检查 TRUSTED_PROXY_HOPS 与反向代理的 X-Forwarded-For 设置");
    return { formError: UNAVAILABLE, accountName };
  }

  let result;
  try {
    result = await uamLogin({ accountName, password, clientIp });
  } catch (error) {
    if (error instanceof UamError) return failureState(error, accountName);
    throw error; // 非 UAM 错误是程序问题，不要伪装成登录失败
  }

  await setSessionCookie(result.sessionId, result.maxAgeSeconds);
  redirect(safeNextPath(formData.get("next")));
}
